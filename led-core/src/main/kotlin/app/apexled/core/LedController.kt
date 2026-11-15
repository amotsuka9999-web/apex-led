package app.apexled.core

import app.apexled.device.BoardCommand
import app.apexled.device.BoardInfo
import app.apexled.device.BoardManager
import app.apexled.pattern.LedFrame
import app.apexled.pattern.PatternEngine
import app.apexled.pattern.Rgb
import app.apexled.pattern.TelemetryHistory
import app.apexled.pattern.WheelSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ties everything together: takes wheel telemetry, runs the pattern engine and hands the
 * resulting frames to the boards. Owns the settings and saves every change.
 *
 * The host app feeds it through [onTelemetry] (any thread). It never touches the host app's
 * own alarms: the LEDs are an extra indicator, not a replacement.
 */
class LedController(
    private val scope: CoroutineScope,
    private val boards: BoardManager,
    private val store: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How often frames are recomputed when no telemetry arrives; pulses and staggering need a steady tick. */
    private val tickMs: Long = 50,
) {
    private val lock = Any()
    private val monitor = CriticalMonitor()
    private val smoother = InputSmoother()

    private val _settings = MutableStateFlow(LedSettings())
    val settings: StateFlow<LedSettings> = _settings.asStateFlow()

    private val _critical = MutableStateFlow(CriticalState.CLEAR)
    val critical: StateFlow<CriticalState> = _critical.asStateFlow()

    private val _frames = MutableStateFlow<List<LedFrame>>(emptyList())

    /** The frames last handed to the boards (empty while the boards run their own effects). For the live preview. */
    val frames: StateFlow<List<LedFrame>> = _frames.asStateFlow()

    private val _position = MutableStateFlow(0.0)

    /** Where the driving input currently sits along the pattern, 0..1. For the marker on the preview strip. */
    val position: StateFlow<Double> = _position.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)

    /** Set when the last attempt to save the settings failed, cleared by the next successful save. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val saveRequests = Channel<LedSettings>(Channel.CONFLATED)
    private var job: Job? = null

    // Guarded by lock.
    private var latest: WheelTelemetry? = null
    private var latestAt = 0L
    private var lastSample = WheelSample.ZERO
    private var hadFreshTelemetry = false
    private var history = TelemetryHistory.EMPTY
    private var driving = false

    /** Loads the saved settings, connects the saved boards and starts producing frames. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val loaded = store.load()
            _settings.value = loaded
            loaded.boards.forEach { boards.add(it) }
            job = scope.launch {
                launch { for (settings in saveRequests) save(settings) }
                while (true) {
                    step()
                    delay(tickMs)
                }
            }
        }
    }

    /** Stops producing frames and disconnects every board. Settings stay saved. */
    suspend fun stop() {
        val running = synchronized(lock) { job.also { job = null } }
        running?.cancelAndJoin()
        boards.removeAll()
        synchronized(lock) {
            driving = false
            _frames.value = emptyList()
        }
    }

    /** A new reading from the wheel. Safe to call from any thread; never blocks on I/O. */
    fun onTelemetry(telemetry: WheelTelemetry) {
        synchronized(lock) {
            val now = clock()
            if (!hadFreshTelemetry) smoother.reset()
            latest = telemetry
            latestAt = now
            hadFreshTelemetry = true
            lastSample = smoother.update(telemetry.toSample(), _settings.value.smoothingMs, now)
        }
        step()
    }

    /** Changes the settings and saves them. Throws [IllegalArgumentException] when the result is invalid. */
    fun update(transform: (LedSettings) -> LedSettings) {
        synchronized(lock) {
            val old = _settings.value
            val new = transform(old)
            require(new.boards == old.boards) { "boards are changed through addBoard / removeBoard / renameBoard" }
            if (new == old) return
            _settings.value = new
            saveRequests.trySend(new)
            if (new.mode == ControlMode.MANUAL && !driving && new.manual != old.manual) applyManual(new.manual)
        }
        step()
    }

    /** Registers a board, connects to it and saves it. */
    fun addBoard(info: BoardInfo): BoardManager.AddResult {
        synchronized(lock) {
            val result = boards.add(info)
            if (result is BoardManager.AddResult.Added) commitBoards()
            return result
        }
    }

    suspend fun removeBoard(id: String): Boolean {
        val removed = boards.remove(id)
        if (removed) synchronized(lock) { commitBoards() }
        return removed
    }

    fun renameBoard(id: String, name: String): Boolean {
        synchronized(lock) {
            val renamed = boards.rename(id, name)
            if (renamed) commitBoards()
            return renamed
        }
    }

    /** Sets the board order, which is the order of the stagger. */
    fun reorderBoards(ids: List<String>): Boolean {
        synchronized(lock) {
            val reordered = boards.reorder(ids)
            if (reordered) commitBoards()
            return reordered
        }
    }

    /**
     * Sends a one-off command to every board (for board options that live on the board itself,
     * such as its power-on state). Returns the ids of boards that do not support it.
     */
    fun sendToBoards(command: BoardCommand): List<String> = boards.sendToAll(command)

    private fun commitBoards() {
        val new = _settings.value.copy(boards = boards.boards.value.map { it.info })
        _settings.value = new
        saveRequests.trySend(new)
    }

    private fun step() {
        synchronized(lock) {
            val now = clock()
            val current = _settings.value
            val fresh = latest?.takeIf { now - latestAt <= current.telemetryTimeoutMs }
            if (fresh == null) hadFreshTelemetry = false

            // Raw telemetry, never the smoothed values: smoothing must not delay the alert.
            val critical = monitor.update(fresh, current.safety, now)
            _critical.value = critical

            val sample: WheelSample? = when {
                fresh != null -> lastSample
                current.telemetryLoss == TelemetryLossBehavior.HOLD_LAST -> lastSample
                current.telemetryLoss == TelemetryLossBehavior.ZERO_INPUT -> WheelSample.ZERO
                else -> null
            }
            history = history.plus(now, sample ?: WheelSample.ZERO)
            _position.value = PatternEngine.position(sample ?: WheelSample.ZERO, current.engine.input)

            val count = boards.size
            val frames = when {
                critical.active -> PatternEngine.frames(history, true, current.engine, count, now)
                current.mode == ControlMode.MANUAL -> null
                sample == null -> List(count) { DARK }
                else -> PatternEngine.frames(history, false, current.engine, count, now)
            }

            if (frames != null) {
                boards.showFrames(frames)
                driving = true
                _frames.value = frames
            } else if (driving) {
                // Hand the boards back to the user's own choice.
                driving = false
                _frames.value = emptyList()
                boards.clearFrames()
                applyManual(current.manual)
            }
        }
    }

    private fun applyManual(manual: ManualLight) {
        manual.toCommands().forEach { boards.sendToAll(it) }
    }

    private fun save(settings: LedSettings) {
        try {
            store.save(settings)
            _saveError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _saveError.value = e.message ?: e.javaClass.simpleName
        }
    }

    private companion object {
        val DARK = LedFrame(Rgb.BLACK, 0.0)
    }
}
