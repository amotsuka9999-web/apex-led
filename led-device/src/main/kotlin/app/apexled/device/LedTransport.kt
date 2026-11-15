package app.apexled.device

import kotlinx.coroutines.flow.Flow

/**
 * The raw link to one LED board: connect, write bytes, receive bytes.
 *
 * It knows nothing about what the bytes mean (that is [app.apexled.device.protocol.LedProtocol]),
 * so the same protocol runs over Android BLE on a phone and over
 * [app.apexled.device.sim.SimulatedBoard] in tests.
 */
interface LedTransport {

    /** Notifications from the board and loss of the link. Hot: only events emitted after subscribing are seen. */
    val events: Flow<TransportEvent>

    /** Brings the link up. Suspends until it is usable; throws [TransportException] when it cannot be established. */
    suspend fun connect()

    /** Tears the link down. Safe to call when already disconnected. */
    suspend fun disconnect()

    /** Sends one payload to the board's write characteristic. Throws [TransportException] when the link is down. */
    suspend fun write(payload: ByteArray)
}

sealed interface TransportEvent {
    class Notification(val payload: ByteArray) : TransportEvent

    data class LinkLost(val reason: String) : TransportEvent
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
