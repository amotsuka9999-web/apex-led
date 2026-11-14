package app.apexled.device.sim

import app.apexled.device.LightMode
import app.apexled.device.protocol.Sp61xPwmProtocol
import app.apexled.device.protocol.Sp621eProtocol
import app.apexled.device.protocol.Sp63xProtocol
import app.apexled.pattern.Rgb

/** The visible state of a simulated board. */
data class SimLight(
    val power: Boolean = false,
    val mode: LightMode = LightMode.DYNAMIC,
    val effect: Int = 1,
    val color: Rgb = Rgb.WHITE,
    val level: Int = 255,
    val effectSpeed: Int = 5,
    val effectLength: Int = 10,
) {
    /** True when the board is lit with a plain solid colour, i.e. frames are visible as sent. */
    val showsSolidColor: Boolean get() = power && mode == LightMode.STATIC
}

/** The part of a simulated board that understands messages. */
interface BoardFirmware {
    val light: SimLight

    /** Number of messages that could not be understood. */
    val rejected: Int

    /** Handles one written payload and returns the notifications it triggers. */
    fun receive(payload: ByteArray): List<ByteArray>

    /** Back to the state of a board that just got power. */
    fun reset()
}

/** Simulated SP63xE / SP64xE, mirroring the layout [Sp63xProtocol] encodes. */
class Sp63xFirmware(private val firmwareVersion: String = "V1.0SIM") : BoardFirmware {

    @Volatile override var light = SimLight()
        private set

    @Volatile override var rejected = 0
        private set

    override fun reset() {
        light = SimLight()
    }

    override fun receive(payload: ByteArray): List<ByteArray> {
        if (payload.size < Sp63xProtocol.HEADER_LENGTH ||
            payload[0] != Sp63xProtocol.HEADER ||
            (payload[5].toInt() and 0xFF) != payload.size - Sp63xProtocol.HEADER_LENGTH
        ) {
            rejected++
            return emptyList()
        }
        val data = IntArray(payload.size - Sp63xProtocol.HEADER_LENGTH) {
            payload[Sp63xProtocol.HEADER_LENGTH + it].toInt() and 0xFF
        }
        val opcode = payload[1].toInt() and 0xFF
        when {
            opcode == Sp63xProtocol.Opcode.STATUS -> return listOf(statusMessage())
            opcode == Sp63xProtocol.Opcode.POWER && data.size == 1 -> light = light.copy(power = data[0] != 0)
            opcode == Sp63xProtocol.Opcode.BRIGHTNESS && data.size == 2 -> light = light.copy(level = data[1])
            opcode == Sp63xProtocol.Opcode.STATIC_COLOR && data.size == 4 ->
                light = light.copy(color = Rgb(data[0], data[1], data[2]), level = data[3])
            opcode == Sp63xProtocol.Opcode.MODE_EFFECT && data.size == 2 -> {
                val mode = Sp63xProtocol.lightMode(data[0])
                if (mode == null) rejected++ else light = light.copy(mode = mode, effect = data[1])
            }
            opcode == Sp63xProtocol.Opcode.EFFECT_SPEED && data.size == 1 -> light = light.copy(effectSpeed = data[0])
            opcode == Sp63xProtocol.Opcode.EFFECT_LENGTH && data.size == 1 -> light = light.copy(effectLength = data[0])
            opcode in ACCEPTED_WITHOUT_EFFECT -> Unit
            else -> rejected++
        }
        return emptyList()
    }

    private fun statusMessage(): ByteArray {
        val current = light
        val message = ByteArray(STATUS_LENGTH)
        message[0] = Sp63xProtocol.HEADER
        message[1] = Sp63xProtocol.Opcode.STATUS.toByte()
        message[3] = 0x01
        message[5] = (STATUS_LENGTH - Sp63xProtocol.HEADER_LENGTH).toByte()
        firmwareVersion.padEnd(7).take(7).toByteArray(Charsets.US_ASCII).copyInto(message, 11)
        message[19] = LIGHT_TYPE_PWM_RGB.toByte()
        message[25] = 0x02
        message[29] = if (current.power) 1 else 0
        message[32] = Sp63xProtocol.modeNumber(current.mode).toByte()
        message[33] = current.effect.toByte()
        message[35] = current.level.toByte()
        message[37] = current.color.r.toByte()
        message[38] = current.color.g.toByte()
        message[39] = current.color.b.toByte()
        message[42] = current.effectSpeed.toByte()
        message[43] = current.effectLength.toByte()
        return message
    }

    private companion object {
        const val STATUS_LENGTH = 53
        const val LIGHT_TYPE_PWM_RGB = 0x85
        val ACCEPTED_WITHOUT_EFFECT = setOf(
            Sp63xProtocol.Opcode.ON_OFF_ANIMATION,
            Sp63xProtocol.Opcode.POWER_ON_STATE,
            Sp63xProtocol.Opcode.EFFECT_DIRECTION,
            Sp63xProtocol.Opcode.EFFECT_COLOR,
            Sp63xProtocol.Opcode.EFFECT_LOOP,
            Sp63xProtocol.Opcode.AUDIO_INPUT,
            Sp63xProtocol.Opcode.SENSITIVITY,
            Sp63xProtocol.Opcode.EFFECT_PLAY,
        )
    }
}

/** Simulated SP613E-style PWM controller, mirroring the layout [Sp61xPwmProtocol] encodes. */
class Sp61xPwmFirmware : BoardFirmware {

    @Volatile override var light = SimLight()
        private set

    @Volatile override var rejected = 0
        private set

    override fun reset() {
        light = SimLight()
    }

    override fun receive(payload: ByteArray): List<ByteArray> {
        if (payload.size < 2 || (payload[1].toInt() and 0xFF) != payload.size - 2) {
            rejected++
            return emptyList()
        }
        val data = IntArray(payload.size - 2) { payload[2 + it].toInt() and 0xFF }
        when (payload[0].toInt() and 0xFF) {
            Sp61xPwmProtocol.Opcode.POWER -> light = light.copy(power = data[0] != 0)
            Sp61xPwmProtocol.Opcode.BRIGHTNESS -> light = light.copy(level = data[0])
            Sp61xPwmProtocol.Opcode.COLOR -> light = light.copy(color = Rgb(data[0], data[1], data[2]), level = data[3])
            Sp61xPwmProtocol.Opcode.EFFECT -> light = light.copy(
                mode = if (data[0] == Sp61xPwmProtocol.EFFECT_SOLID) LightMode.STATIC else LightMode.DYNAMIC,
                effect = data[0],
            )
            Sp61xPwmProtocol.Opcode.EFFECT_SPEED -> light = light.copy(effectSpeed = data[0])
            Sp61xPwmProtocol.Opcode.STATUS -> Unit
            else -> rejected++
        }
        return emptyList()
    }
}

/** Simulated SP621E, mirroring the layout [Sp621eProtocol] encodes. */
class Sp621eFirmware : BoardFirmware {

    @Volatile override var light = SimLight()
        private set

    @Volatile override var rejected = 0
        private set

    override fun reset() {
        light = SimLight()
    }

    override fun receive(payload: ByteArray): List<ByteArray> {
        if (payload.size < 3 || (payload[0].toInt() and 0xFF) != Sp621eProtocol.HEADER ||
            (payload[2].toInt() and 0xFF) != payload.size - 3
        ) {
            rejected++
            return emptyList()
        }
        val data = IntArray(payload.size - 3) { payload[3 + it].toInt() and 0xFF }
        when (payload[1].toInt() and 0xFF) {
            Sp621eProtocol.Opcode.POWER -> light = light.copy(power = data[0] != 0)
            Sp621eProtocol.Opcode.BRIGHTNESS -> light = light.copy(level = data[0])
            Sp621eProtocol.Opcode.COLOR -> light = light.copy(color = Rgb(data[0], data[1], data[2]), level = data[3])
            Sp621eProtocol.Opcode.EFFECT -> light = light.copy(
                mode = if (data[0] == Sp621eProtocol.EFFECT_SOLID) LightMode.STATIC else LightMode.DYNAMIC,
                effect = data[0],
            )
            Sp621eProtocol.Opcode.EFFECT_SPEED -> light = light.copy(effectSpeed = data[0])
            Sp621eProtocol.Opcode.EFFECT_LENGTH -> light = light.copy(effectLength = data[0])
            else -> rejected++
        }
        return emptyList()
    }
}
