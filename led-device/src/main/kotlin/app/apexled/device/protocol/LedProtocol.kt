package app.apexled.device.protocol

import app.apexled.device.BoardCommand
import app.apexled.device.BoardStatus
import app.apexled.pattern.LedFrame

/**
 * Turns board functions into the bytes one controller family understands, and back.
 * Stateless. Swap the implementation to support another board, or to correct an encoding
 * after checking it on real hardware, without touching anything above this interface.
 */
interface LedProtocol {

    /** Stable identifier stored with each board in the settings. */
    val id: String

    val displayName: String

    /** Payloads that put the board in a state where [frame] payloads take effect: powered, solid-colour mode. */
    fun enterDirectColor(): List<ByteArray>

    /** Payloads that show one frame of a telemetry-driven pattern. */
    fun frame(frame: LedFrame): List<ByteArray>

    /** Payloads for [command], or null when this family has no such function. */
    fun encode(command: BoardCommand): List<ByteArray>?

    /** Decodes a notification into a status, or null when it is not a (complete, valid) status message. */
    fun parseStatus(notification: ByteArray): BoardStatus?
}

/** The protocols the app can talk. */
class ProtocolRegistry(protocols: List<LedProtocol> = listOf(Sp63xProtocol, Sp61xPwmProtocol)) {
    private val byId = protocols.associateBy { it.id }

    init {
        require(byId.size == protocols.size) { "duplicate protocol id" }
    }

    val all: List<LedProtocol> = protocols

    operator fun get(id: String): LedProtocol? = byId[id]

    fun require(id: String): LedProtocol = requireNotNull(byId[id]) { "unknown protocol '$id'" }
}
