package app.apexled.device.protocol

import app.apexled.device.BoardCommand
import app.apexled.device.BoardStatus
import app.apexled.device.LightMode
import app.apexled.pattern.LedFrame

/**
 * SP613E / SP614E / SP623E / SP624E PWM controller family (the earlier, simpler framing).
 *
 * Written from the publicly documented message layout - see docs/protocolo-led.md. Not verified
 * against a physical board. Only the functions needed to drive colour patterns and the basic
 * controls are encoded; status messages arrive split over several notifications in this family
 * and are not parsed yet.
 *
 * Message: `<opcode> <length> <data...>`, written to characteristic FFE1 of service FFE0.
 */
object Sp61xPwmProtocol : LedProtocol {

    override val id = "sp61x-pwm"
    override val displayName = "SP613E / SP614E / SP623E / SP624E"

    object Opcode {
        const val POWER = 0x0F
        const val BRIGHTNESS = 0x12
        const val COLOR = 0x13
        const val EFFECT_SPEED = 0x14
        const val EFFECT = 0x15
        const val STATUS = 0x1D
    }

    /** Effect number of the plain solid colour. */
    const val EFFECT_SOLID = 0x63

    fun message(opcode: Int, vararg data: Int): ByteArray {
        val bytes = ByteArray(2 + data.size)
        bytes[0] = opcode.toByte()
        bytes[1] = data.size.toByte()
        data.forEachIndexed { index, value -> bytes[2 + index] = value.toByte() }
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
        // This family numbers all effects in one list; the mode only matters for STATIC.
        is BoardCommand.SelectEffect -> listOf(
            message(Opcode.EFFECT, if (command.mode == LightMode.STATIC) EFFECT_SOLID else command.effect),
        )
        is BoardCommand.EffectSpeed -> listOf(message(Opcode.EFFECT_SPEED, command.speed))
        BoardCommand.QueryStatus -> listOf(message(Opcode.STATUS))
        else -> null
    }

    override fun parseStatus(notification: ByteArray): BoardStatus? = null
}
