package app.apexled.device.sim

import app.apexled.device.LedTransport
import app.apexled.device.TransportEvent
import app.apexled.device.TransportException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A board that exists only in memory: a [LedTransport] with a [BoardFirmware] behind it.
 * Lets the whole stack above the radio be exercised without hardware, including the
 * failures that are awkward to produce with a real board.
 *
 * It models the link and the documented message layout. It cannot tell whether a real
 * controller behaves the same way - see docs/relatorio-testes.md.
 */
class SimulatedBoard(
    val firmware: BoardFirmware = Sp63xFirmware(),
    private val clock: () -> Long = System::currentTimeMillis,
) : LedTransport {

    class Write(val timeMs: Long, val payload: ByteArray)

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<TransportEvent> = _events

    private val lock = Any()
    private val _writes = mutableListOf<Write>()

    /** Every payload the board accepted, oldest first. */
    val writes: List<Write> get() = synchronized(lock) { _writes.toList() }

    @Volatile var connected = false
        private set

    /** Number of successful [connect] calls. */
    @Volatile var connectCount = 0
        private set

    /** When false the board is out of range or unpowered: [connect] fails. */
    @Volatile var reachable = true

    /** Time [connect] takes. */
    @Volatile var connectDelayMs = 0L

    /** Time each [write] takes. */
    @Volatile var writeDelayMs = 0L

    /** The next this-many writes fail without dropping the link. */
    @Volatile var failNextWrites = 0

    /** When true the board resets its light state on every new connection, like a controller that lost power. */
    @Volatile var resetOnConnect = false

    override suspend fun connect() {
        delay(connectDelayMs)
        if (!reachable) throw TransportException("board is not reachable")
        if (resetOnConnect) firmware.reset()
        connected = true
        connectCount++
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun write(payload: ByteArray) {
        delay(writeDelayMs)
        if (!connected) throw TransportException("not connected")
        if (failNextWrites > 0) {
            failNextWrites--
            throw TransportException("write failed")
        }
        synchronized(lock) { _writes += Write(clock(), payload.copyOf()) }
        firmware.receive(payload).forEach { _events.tryEmit(TransportEvent.Notification(it)) }
    }

    /** The link drops (out of range, power cut). The board stays reachable unless [reachable] is cleared too. */
    fun dropLink(reason: String = "simulated link loss") {
        if (!connected) return
        connected = false
        _events.tryEmit(TransportEvent.LinkLost(reason))
    }

    /** Takes the board away entirely: the link drops and reconnection fails until [powerOn]. */
    fun powerOff() {
        reachable = false
        dropLink("simulated power off")
    }

    fun powerOn() {
        reachable = true
    }

    fun clearWrites() {
        synchronized(lock) { _writes.clear() }
    }
}
