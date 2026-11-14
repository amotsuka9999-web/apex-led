package app.apexled.device

import app.apexled.device.protocol.LedProtocol
import app.apexled.pattern.LedFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps one board connected and shows on it what the app wants shown.
 *
 * Two kinds of work reach a board:
 *  - frames ([showFrame]): only the newest matters. A frame that was not sent yet is simply
 *    replaced, so a slow link shows the current colour late-free instead of replaying a backlog;
 *  - board commands ([send]): sent in order, kept while the link is down, never merged.
 *
 * A critical frame goes out before any waiting command. After a reconnection the board is
 * set up again and the current frame is re-sent.
 */
class BoardConnection(
    private val transport: LedTransport,
    val protocol: LedProtocol,
    private val scope: CoroutineScope,
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<BoardState>(BoardState.Disconnected)
    val state: StateFlow<BoardState> = _state.asStateFlow()

    private val _status = MutableStateFlow<BoardStatus?>(null)

    /** Last status the board reported, if its protocol can read one. */
    val status: StateFlow<BoardStatus?> = _status.asStateFlow()

    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var desiredFrame: LedFrame? = null
    private val commands = ArrayDeque<BoardCommand>()
    private var job: Job? = null

    // Touched only by the worker coroutine.
    private var lastWriteAt = NEVER
    private var lastFrame: LedFrame? = null
    private var lastFrameAt = NEVER
    private var directColorReady = false

    /** Starts connecting, and reconnecting whenever the link drops. Does nothing if already started. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { run() }
        }
    }

    /** Stops for good: closes the link and stops reconnecting. Waiting commands are discarded. */
    suspend fun stop() {
        val running = synchronized(lock) { job.also { job = null } }
        running?.cancelAndJoin()
        synchronized(lock) { commands.clear() }
        _state.value = BoardState.Disconnected
    }

    /** Makes [frame] the thing to show. Replaces any frame not sent yet. */
    fun showFrame(frame: LedFrame) {
        synchronized(lock) { desiredFrame = frame }
        wake.trySend(Unit)
    }

    /** Stops driving the colour, leaving the board on whatever it shows (used when the board's own effects take over). */
    fun clearFrame() {
        synchronized(lock) { desiredFrame = null }
    }

    /** Queues a board command. Returns false when the board's protocol has no such function. */
    fun send(command: BoardCommand): Boolean {
        if (protocol.encode(command) == null) return false
        synchronized(lock) {
            if (commands.size >= policy.maxQueuedCommands) commands.removeFirst()
            commands.addLast(command)
        }
        wake.trySend(Unit)
        return true
    }

    private suspend fun run() {
        var attempt = 0
        try {
            while (true) {
                if (attempt == 0) {
                    _state.value = BoardState.Connecting
                } else {
                    val wait = policy.backoffMs(attempt)
                    _state.value = BoardState.Reconnecting(attempt, wait)
                    delay(wait)
                }
                if (!connect()) {
                    attempt++
                    continue
                }
                _state.value = BoardState.Ready
                val sessionStartedAt = clock()
                session()
                closeQuietly()
                // A link that held for a while earns a fast first retry; one that dies at once keeps backing off.
                attempt = if (clock() - sessionStartedAt >= policy.stableSessionMs) 1 else attempt + 1
            }
        } finally {
            withContext(NonCancellable) { closeQuietly() }
        }
    }

    private suspend fun connect(): Boolean = try {
        withTimeoutOrNull(policy.connectTimeoutMs) { transport.connect() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun closeQuietly() {
        try {
            transport.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing useful to do: the link is being abandoned anyway.
        }
    }

    /** Serves the board until the link is lost. */
    private suspend fun session() = coroutineScope {
        lastFrame = null
        lastFrameAt = NEVER
        directColorReady = false

        val linkLost = CompletableDeferred<String>()
        // Undispatched: subscribed before this coroutine does anything else, so no event is missed.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Notification -> protocol.parseStatus(event.payload)?.let { _status.value = it }
                    is TransportEvent.LinkLost -> linkLost.complete(event.reason)
                }
            }
        }
        try {
            protocol.encode(BoardCommand.QueryStatus)?.forEach { write(it) }
            while (!linkLost.isCompleted) {
                when (val work = nextWork()) {
                    is Work.ShowFrame -> sendFrame(work.frame)
                    is Work.Run -> if (!sendCommand(work.command)) break
                    is Work.Idle -> withTimeoutOrNull(work.forMs) {
                        select {
                            wake.onReceive { }
                            linkLost.onAwait { }
                        }
                    }
                }
            }
        } catch (_: TransportException) {
            // Falls through to reconnection.
        } finally {
            listener.cancel()
        }
    }

    private sealed interface Work {
        class ShowFrame(val frame: LedFrame) : Work
        class Run(val command: BoardCommand) : Work
        class Idle(val forMs: Long) : Work
    }

    private fun nextWork(): Work {
        val now = clock()
        synchronized(lock) {
            val frame = desiredFrame
            val frameDue = frame != null && isDue(frame, now)
            if (frame != null && frameDue && frame.critical) return Work.ShowFrame(frame)
            commands.removeFirstOrNull()?.let { return Work.Run(it) }
            if (frame != null && frameDue) return Work.ShowFrame(frame)
            if (frame == null) return Work.Idle(policy.refreshIntervalMs)
            return Work.Idle((lastFrameAt + refreshInterval(frame) - now).coerceAtLeast(1))
        }
    }

    private fun isDue(frame: LedFrame, now: Long): Boolean =
        !sameLight(frame, lastFrame) || now - lastFrameAt >= refreshInterval(frame)

    private fun refreshInterval(frame: LedFrame) =
        if (frame.critical) policy.criticalRefreshIntervalMs else policy.refreshIntervalMs

    /** Frames that put the same bytes on the wire are the same light. */
    private fun sameLight(a: LedFrame, b: LedFrame?) =
        b != null && a.color == b.color && a.level == b.level && a.critical == b.critical

    private suspend fun sendFrame(frame: LedFrame) {
        if (!directColorReady) {
            protocol.enterDirectColor().forEach { write(it) }
            directColorReady = true
        }
        protocol.frame(frame).forEach { write(it) }
        lastFrame = frame
        lastFrameAt = clock()
    }

    /** Returns false when the command could not be delivered and was put back for the next session. */
    private suspend fun sendCommand(command: BoardCommand): Boolean {
        val payloads = protocol.encode(command) ?: return true
        try {
            payloads.forEach { write(it) }
        } catch (e: TransportException) {
            synchronized(lock) { commands.addFirst(command) }
            return false
        }
        if (command !is BoardCommand.QueryStatus) {
            // The command may have changed mode or colour: set the board up again before the next frame.
            directColorReady = false
            lastFrame = null
        }
        return true
    }

    private suspend fun write(payload: ByteArray) {
        val wait = lastWriteAt + policy.minWriteIntervalMs - clock()
        if (lastWriteAt != NEVER && wait > 0) delay(wait)
        val written = withTimeoutOrNull(policy.writeTimeoutMs) { transport.write(payload) }
        lastWriteAt = clock()
        if (written == null) throw TransportException("write timed out after ${policy.writeTimeoutMs} ms")
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
    }
}
