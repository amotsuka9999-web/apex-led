package app.apexled.core

import app.apexled.device.BoardCommand
import app.apexled.device.BoardInfo
import app.apexled.device.BoardManager
import app.apexled.device.LightMode
import app.apexled.pattern.EngineConfig
import app.apexled.pattern.Rgb

/** Who decides what the boards show. */
enum class ControlMode {
    /** The wheel's telemetry drives a colour pattern. */
    PATTERN,

    /** The user picks colour and effects by hand, as in the manufacturer's app. */
    MANUAL,
}

/** What a pattern shows when no fresh telemetry is arriving (wheel disconnected). */
enum class TelemetryLossBehavior {
    /** As if speed and PWM were zero: lit, at the cold end of the pattern. */
    ZERO_INPUT,

    /** Keep showing the last known values. */
    HOLD_LAST,

    /** Dark. */
    OFF,
}

/** The hand-picked light of [ControlMode.MANUAL]: the board's own functions. */
data class ManualLight(
    val power: Boolean = true,
    val mode: LightMode = LightMode.STATIC,
    val color: Rgb = Rgb.WHITE,
    /** Brightness, 0..255. */
    val level: Int = 255,
    /** Built-in effect number, used when [mode] is not STATIC. */
    val effect: Int = 1,
    val effectSpeed: Int = 5,
    val effectLength: Int = 10,
) {
    init {
        require(level in 0..255) { "level must be 0..255" }
        require(effect in 0..255) { "effect must be 0..255" }
        require(effectSpeed in 1..10) { "effectSpeed must be 1..10" }
        require(effectLength in 1..150) { "effectLength must be 1..150" }
    }

    /** The board commands that put a board in this state. */
    fun toCommands(): List<BoardCommand> = when {
        !power -> listOf(BoardCommand.Power(false))
        mode == LightMode.STATIC -> listOf(BoardCommand.Power(true), BoardCommand.StaticColor(color, level))
        else -> listOf(
            BoardCommand.Power(true),
            BoardCommand.SelectEffect(mode, effect),
            BoardCommand.Brightness(level),
            BoardCommand.EffectSpeed(effectSpeed),
            BoardCommand.EffectLength(effectLength),
        )
    }
}

/** Everything the user configures. Persisted between sessions by a [SettingsStore]. */
data class LedSettings(
    val mode: ControlMode = ControlMode.PATTERN,
    /** Pattern, input, brightness and sync: the same for every board. */
    val engine: EngineConfig = EngineConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val manual: ManualLight = ManualLight(),
    /** Registered boards, in stagger order. */
    val boards: List<BoardInfo> = emptyList(),
    /** Time constant of the input smoothing, 0 = off. */
    val smoothingMs: Long = 250,
    val telemetryLoss: TelemetryLossBehavior = TelemetryLossBehavior.ZERO_INPUT,
    /** Telemetry older than this counts as lost. */
    val telemetryTimeoutMs: Long = 3_000,
) {
    init {
        require(boards.size <= BoardManager.MAX_BOARDS) { "at most ${BoardManager.MAX_BOARDS} boards" }
        require(boards.map { it.id }.toSet().size == boards.size) { "board ids must be unique" }
        require(smoothingMs in 0..MAX_SMOOTHING_MS) { "smoothingMs must be 0..$MAX_SMOOTHING_MS" }
        require(telemetryTimeoutMs in 500..60_000) { "telemetryTimeoutMs must be 500..60000" }
    }

    companion object {
        const val MAX_SMOOTHING_MS = 5_000L
    }
}
