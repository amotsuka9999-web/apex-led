package app.apexled.device

import app.apexled.pattern.Rgb

/** Light modes of the board itself, as exposed by the manufacturer's app. */
enum class LightMode { STATIC, DYNAMIC, MUSIC, CUSTOM }

/** What the board does when it gets power. */
enum class PowerOnState { OFF, ON, LAST_STATE }

/** Where a music-reactive effect takes its sound from. */
enum class AudioInput { BUILT_IN_MIC, PHONE_PLAYER, EXTERNAL_MIC }

enum class OnOffAnimation { FLOW_FORWARD, FLOW_BACKWARD, GRADIENT, STARS }

enum class OnOffSpeed { SLOW, MEDIUM, FAST }

/**
 * A function of the board, independent of how any protocol encodes it.
 * These are the board's own features (R1 parity), not the telemetry-driven patterns.
 */
sealed interface BoardCommand {
    data class Power(val on: Boolean) : BoardCommand

    /** Overall brightness, 0..255. */
    data class Brightness(val level: Int) : BoardCommand {
        init { require(level in 0..255) { "level must be 0..255" } }
    }

    /** Solid colour at a brightness level, 0..255. */
    data class StaticColor(val color: Rgb, val level: Int) : BoardCommand {
        init { require(level in 0..255) { "level must be 0..255" } }
    }

    /** Selects a built-in effect of [mode] by its number on the board. */
    data class SelectEffect(val mode: LightMode, val effect: Int) : BoardCommand {
        init { require(effect in 0..255) { "effect must be 0..255" } }
    }

    /** Effect speed, 1..10. */
    data class EffectSpeed(val speed: Int) : BoardCommand {
        init { require(speed in 1..10) { "speed must be 1..10" } }
    }

    /** Effect length, 1..150. */
    data class EffectLength(val length: Int) : BoardCommand {
        init { require(length in 1..150) { "length must be 1..150" } }
    }

    data class EffectDirection(val forward: Boolean) : BoardCommand

    /** Cycle through the effects of the current mode. */
    data class EffectLoop(val on: Boolean) : BoardCommand

    data class EffectPlay(val playing: Boolean) : BoardCommand

    /** Colour used by effects that take one. */
    data class EffectColor(val color: Rgb) : BoardCommand

    data class SelectAudioInput(val input: AudioInput) : BoardCommand

    /** Microphone sensitivity, 1..16. */
    data class Sensitivity(val gain: Int) : BoardCommand {
        init { require(gain in 1..16) { "gain must be 1..16" } }
    }

    data class SetPowerOnState(val state: PowerOnState) : BoardCommand

    data class SetOnOffAnimation(val animation: OnOffAnimation, val speed: OnOffSpeed, val pixels: Int) : BoardCommand {
        init { require(pixels in 1..600) { "pixels must be 1..600" } }
    }

    data object QueryStatus : BoardCommand
}

/** What a board reported about itself. Fields a protocol cannot read stay null. */
data class BoardStatus(
    val power: Boolean,
    val mode: LightMode? = null,
    val effect: Int? = null,
    val color: Rgb? = null,
    val level: Int? = null,
    val effectSpeed: Int? = null,
    val effectLength: Int? = null,
    val firmware: String? = null,
    val lightType: Int? = null,
    val powerOnState: PowerOnState? = null,
)

/** A board the user registered. [id] is stable; [name] is editable; [address] is the BLE MAC. */
data class BoardInfo(
    val id: String,
    val name: String,
    val address: String,
    val protocolId: String,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(address.isNotBlank()) { "address must not be blank" }
    }
}

sealed interface BoardState {
    /** Not started, or stopped by the user. */
    data object Disconnected : BoardState

    data object Connecting : BoardState

    data object Ready : BoardState

    /** The link failed; attempt number [attempt] starts in [retryInMs]. */
    data class Reconnecting(val attempt: Int, val retryInMs: Long) : BoardState
}
