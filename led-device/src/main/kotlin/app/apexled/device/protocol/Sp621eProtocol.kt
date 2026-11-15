package app.apexled.device.protocol

import app.apexled.device.BoardCommand
import app.apexled.device.BoardStatus
import app.apexled.device.LightMode
import app.apexled.pattern.LedFrame

/**
 * SP621E (and the SP611E / SP617E / SP620E group): the board named in the specification.
 *
 * Written from the publicly documented message layout - see docs/protocolo-led.md. Not verified
 * against a physical board. Status messages are not documented in the sources and are not read.
 *
 * Message: `A0 <opcode> <length> <data...>`, written to characteristic FFE1 of service FFE0.
 */
object Sp621eProtocol : LedProtocol {

    override val id = "sp621e"
    override val displayName = "SP621E"

    const val HEADER = 0xA0

    object Opcode {
        const val POWER = 0x62
        const val EFFECT = 0x63
        const val BRIGHTNESS = 0x66
        const val EFFECT_SPEED = 0x67
        const val EFFECT_LENGTH = 0x68
        const val COLOR = 0x69
    }

    /** Effect number that selects the plain solid colour. */
    const val EFFECT_SOLID = 0xBE

    fun message(opcode: Int, vararg data: Int): ByteArray {
        val bytes = ByteArray(3 + data.size)
        bytes[0] = HEADER.toByte()
        bytes[1] = opcode.toByte()
        bytes[2] = data.size.toByte()
        data.forEachIndexed { index, value -> bytes[3 + index] = value.toByte() }
        return bytes
    }

    override fun enterDirectColor(): List<ByteArray> = listOf(
        message(Opcode.POWER, 1),
        message(Opcode.EFFECT, EFFECT_SOLID),
    )

    override fun frame(frame: LedFrame): List<ByteArray> =
        listOf(message(Opcode.COLOR, frame.color.r, frame.color.g, frame.color.b, frame.level))

    override fun encode(command: BoardCommand): List<ByteArray>? = when (command) {
        is BoardCommand.Power -> listOf(message(Opcode.POWER, if (command.on) 1 else 0))
        is BoardCommand.Brightness -> listOf(message(Opcode.BRIGHTNESS, command.level))
        is BoardCommand.StaticColor -> listOf(
            message(Opcode.EFFECT, EFFECT_SOLID),
            message(Opcode.COLOR, command.color.r, command.color.g, command.color.b, command.level),
        )
        is BoardCommand.SelectEffect -> listOf(
            message(Opcode.EFFECT, if (command.mode == LightMode.STATIC) EFFECT_SOLID else command.effect),
        )
        is BoardCommand.EffectSpeed -> listOf(message(Opcode.EFFECT_SPEED, command.speed))
        is BoardCommand.EffectLength -> listOf(message(Opcode.EFFECT_LENGTH, command.length))
        else -> null
    }

    override fun parseStatus(notification: ByteArray): BoardStatus? = null
}
