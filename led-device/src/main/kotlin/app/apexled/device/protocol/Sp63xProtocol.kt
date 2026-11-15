package app.apexled.device.protocol

import app.apexled.device.AudioInput
import app.apexled.device.BoardCommand
import app.apexled.device.BoardStatus
import app.apexled.device.LightMode
import app.apexled.device.OnOffAnimation
import app.apexled.device.OnOffSpeed
import app.apexled.device.PowerOnState
import app.apexled.pattern.LedFrame
import app.apexled.pattern.Rgb

/**
 * SP63xE / SP64xE controller family (the "BanlanX" app generation).
 *
 * Written from the publicly documented message layout - see docs/protocolo-led.md for the
 * sources and for what still has to be confirmed on real hardware. Nothing here has been
 * verified against a physical board yet.
 *
 * Message: `53 <opcode> 00 01 00 <length> <data...>`, written to characteristic FFE1 of service FFE0.
 * The third byte is an obfuscation key; 00 means plain data, which is all this codec sends or reads.
 */
object Sp63xProtocol : LedProtocol {

    override val id = "sp63x"
    override val displayName = "SP63xE / SP64xE"

    const val HEADER: Byte = 0x53
    const val HEADER_LENGTH = 6

    object Opcode {
        const val STATUS = 0x02
        const val ON_OFF_ANIMATION = 0x08
        const val POWER_ON_STATE = 0x0B
        const val POWER = 0x50
        const val BRIGHTNESS = 0x51
        const val STATIC_COLOR = 0x52
        const val MODE_EFFECT = 0x53
        const val EFFECT_SPEED = 0x54
        const val EFFECT_LENGTH = 0x55
        const val EFFECT_DIRECTION = 0x56
        const val EFFECT_COLOR = 0x57
        const val EFFECT_LOOP = 0x58
        const val AUDIO_INPUT = 0x59
        const val SENSITIVITY = 0x5A
        const val EFFECT_PLAY = 0x5D
    }

    /** Light mode numbers on the wire (colour variants). */
    object Mode {
        const val STATIC_COLOR = 0x01
        const val DYNAMIC_COLOR = 0x03
        const val SOUND_COLOR = 0x05
        const val CUSTOM_COLOR = 0x07
    }

    /** Effect number of the plain solid colour in static mode. */
    const val EFFECT_SOLID = 0x01

    /** Smallest status message this codec reads: header plus the fields up to the effect colour. */
    const val STATUS_MIN_LENGTH = 50

    fun message(opcode: Int, vararg data: Int): ByteArray {
        require(data.size <= 255) { "payload too long" }
        val bytes = ByteArray(HEADER_LENGTH + data.size)
        bytes[0] = HEADER
        bytes[1] = opcode.toByte()
        bytes[2] = 0x00
        bytes[3] = 0x01
        bytes[4] = 0x00
        bytes[5] = data.size.toByte()
        data.forEachIndexed { index, value -> bytes[HEADER_LENGTH + index] = value.toByte() }
        return bytes
    }

    override fun enterDirectColor(): List<ByteArray> = listOf(
        message(Opcode.POWER, 1),
        message(Opcode.MODE_EFFECT, Mode.STATIC_COLOR, EFFECT_SOLID),
    )

    override fun frame(frame: LedFrame): List<ByteArray> = listOf(staticColor(frame.color, frame.level))

    override fun encode(command: BoardCommand): List<ByteArray>? = when (command) {
        is BoardCommand.Power -> listOf(message(Opcode.POWER, flag(command.on)))
        // First data byte selects the colour (0) or the white (1) channel group.
        is BoardCommand.Brightness -> listOf(message(Opcode.BRIGHTNESS, 0, command.level))
        is BoardCommand.StaticColor -> listOf(
            message(Opcode.MODE_EFFECT, Mode.STATIC_COLOR, EFFECT_SOLID),
            staticColor(command.color, command.level),
        )
        is BoardCommand.SelectEffect -> listOf(message(Opcode.MODE_EFFECT, modeNumber(command.mode), command.effect))
        is BoardCommand.EffectSpeed -> listOf(message(Opcode.EFFECT_SPEED, command.speed))
        is BoardCommand.EffectLength -> listOf(message(Opcode.EFFECT_LENGTH, command.length))
        is BoardCommand.EffectDirection -> listOf(message(Opcode.EFFECT_DIRECTION, flag(command.forward)))
        is BoardCommand.EffectLoop -> listOf(message(Opcode.EFFECT_LOOP, flag(command.on)))
        is BoardCommand.EffectPlay -> listOf(message(Opcode.EFFECT_PLAY, flag(command.playing)))
        is BoardCommand.EffectColor -> listOf(
            message(Opcode.EFFECT_COLOR, command.color.r, command.color.g, command.color.b),
        )
        is BoardCommand.SelectAudioInput -> listOf(message(Opcode.AUDIO_INPUT, audioInputNumber(command.input)))
        is BoardCommand.Sensitivity -> listOf(message(Opcode.SENSITIVITY, command.gain))
        is BoardCommand.SetPowerOnState -> listOf(message(Opcode.POWER_ON_STATE, powerOnNumber(command.state)))
        is BoardCommand.SetOnOffAnimation -> listOf(
            message(
                Opcode.ON_OFF_ANIMATION,
                1,
                animationNumber(command.animation),
                animationSpeedNumber(command.speed),
                command.pixels shr 8,
                command.pixels and 0xFF,
            ),
        )
        BoardCommand.QueryStatus -> listOf(message(Opcode.STATUS, 1))
    }

    override fun parseStatus(notification: ByteArray): BoardStatus? {
        if (notification.size < STATUS_MIN_LENGTH) return null
        if (notification[0] != HEADER || unsigned(notification, 1) != Opcode.STATUS) return null
        if (unsigned(notification, 5) + HEADER_LENGTH != notification.size) return null
        // A non-zero key means the payload is obfuscated, which this codec does not handle.
        if (notification[2] != 0.toByte()) return null

        val modeNumber = unsigned(notification, 32)
        val firmware = String(notification, 11, 7, Charsets.US_ASCII).trim { it <= ' ' }
        return BoardStatus(
            power = unsigned(notification, 29) != 0,
            mode = lightMode(modeNumber),
            effect = unsigned(notification, 33),
            color = Rgb(unsigned(notification, 37), unsigned(notification, 38), unsigned(notification, 39)),
            level = unsigned(notification, 35),
            effectSpeed = unsigned(notification, 42),
            effectLength = unsigned(notification, 43),
            firmware = firmware.ifEmpty { null },
            lightType = unsigned(notification, 19),
            powerOnState = PowerOnState.entries.getOrNull(unsigned(notification, 25)),
        )
    }

    private fun staticColor(color: Rgb, level: Int) =
        message(Opcode.STATIC_COLOR, color.r, color.g, color.b, level)

    internal fun modeNumber(mode: LightMode) = when (mode) {
        LightMode.STATIC -> Mode.STATIC_COLOR
        LightMode.DYNAMIC -> Mode.DYNAMIC_COLOR
        LightMode.MUSIC -> Mode.SOUND_COLOR
        LightMode.CUSTOM -> Mode.CUSTOM_COLOR
    }

    // The white variants (2, 4, 6) map to the same user-facing mode.
    internal fun lightMode(number: Int): LightMode? = when (number) {
        0x01, 0x02 -> LightMode.STATIC
        0x03, 0x04 -> LightMode.DYNAMIC
        0x05, 0x06 -> LightMode.MUSIC
        0x07 -> LightMode.CUSTOM
        else -> null
    }

    private fun audioInputNumber(input: AudioInput) = when (input) {
        AudioInput.BUILT_IN_MIC -> 0
        AudioInput.PHONE_PLAYER -> 1
        AudioInput.EXTERNAL_MIC -> 2
    }

    private fun powerOnNumber(state: PowerOnState) = when (state) {
        PowerOnState.OFF -> 0
        PowerOnState.ON -> 1
        PowerOnState.LAST_STATE -> 2
    }

    private fun animationNumber(animation: OnOffAnimation) = when (animation) {
        OnOffAnimation.FLOW_FORWARD -> 1
        OnOffAnimation.FLOW_BACKWARD -> 2
        OnOffAnimation.GRADIENT -> 3
        OnOffAnimation.STARS -> 4
    }

    private fun animationSpeedNumber(speed: OnOffSpeed) = when (speed) {
        OnOffSpeed.SLOW -> 1
        OnOffSpeed.MEDIUM -> 2
        OnOffSpeed.FAST -> 3
    }

    private fun flag(value: Boolean) = if (value) 1 else 0

    private fun unsigned(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF
}
