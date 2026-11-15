package app.apexled.core

import app.apexled.device.BoardInfo
import app.apexled.device.LightMode
import app.apexled.pattern.BrightnessConfig
import app.apexled.pattern.CriticalStyle
import app.apexled.pattern.EngineConfig
import app.apexled.pattern.IdleLight
import app.apexled.pattern.InputConfig
import app.apexled.pattern.InputSource
import app.apexled.pattern.Pattern
import app.apexled.pattern.PulseItem
import app.apexled.pattern.PulseShape
import app.apexled.pattern.Rgb
import app.apexled.pattern.SyncMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-disk form of [LedSettings]. Kept separate from the domain classes so the file format
 * stays stable when the code is refactored, and so the pattern engine needs no serialisation library.
 *
 * Format rules: colours are `#RRGGBB`, enums are their names, unknown keys are ignored,
 * missing keys take their defaults. Bump [VERSION] and add a migration when a key changes meaning.
 */
object SettingsJson {

    const val VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(settings: LedSettings): String = json.encodeToString(SettingsFile.serializer(), settings.toFile())

    /** Throws [IllegalArgumentException] when [text] is not valid settings JSON or holds out-of-range values. */
    fun decode(text: String): LedSettings {
        val file = json.decodeFromString(SettingsFile.serializer(), text)
        require(file.version in 1..VERSION) { "unsupported settings version ${file.version}" }
        return file.toSettings()
    }

    @Serializable
    private data class SettingsFile(
        val version: Int = VERSION,
        val mode: ControlMode = ControlMode.PATTERN,
        val pattern: PatternDto = PatternDto.PwmSpectrum,
        val input: InputDto = InputDto(),
        val brightness: BrightnessDto = BrightnessDto(),
        val sync: SyncDto = SyncDto(),
        val criticalStyle: CriticalStyle = CriticalStyle.FLASH,
        val safety: SafetyDto = SafetyDto(),
        val manual: ManualDto = ManualDto(),
        val boards: List<BoardDto> = emptyList(),
        val smoothingMs: Long = 250,
        val telemetryLoss: TelemetryLossBehavior = TelemetryLossBehavior.ZERO_INPUT,
        val telemetryTimeoutMs: Long = 3_000,
    )

    @Serializable
    private sealed interface PatternDto {
        @Serializable
        @SerialName("pwm-spectrum")
        data object PwmSpectrum : PatternDto

        @Serializable
        @SerialName("custom-spectrum")
        data class CustomSpectrum(val colors: List<String>) : PatternDto

        @Serializable
        @SerialName("speed-blink")
        data class SpeedBlink(val first: String, val second: String, val maxHz: Double = 5.0) : PatternDto

        @Serializable
        @SerialName("continuous-gradient")
        data object ContinuousGradient : PatternDto

        @Serializable
        @SerialName("three-color-gradient")
        data class ThreeColorGradient(val start: String, val middle: String, val end: String, val midpoint: Double = 0.5) : PatternDto

        @Serializable
        @SerialName("pulse")
        data class Pulse(val items: List<PulseItemDto>, val idle: IdleDto? = null) : PatternDto

        @Serializable
        @SerialName("color-sequence")
        data class ColorSequence(val colors: List<String>, val smooth: Boolean = true) : PatternDto
    }

    @Serializable
    private data class PulseItemDto(
        val color: String,
        val frequencyHz: Double = 1.0,
        val rangeStartPercent: Double = 0.0,
        val rangeEndPercent: Double = 100.0,
        val shape: PulseShape = PulseShape.BLINK,
        val minBrightness: Double = 0.0,
        val maxBrightness: Double = 1.0,
        val dutyCycle: Double = 0.5,
    )

    /** A steady idle light; absent means dark. */
    @Serializable
    private data class IdleDto(val color: String, val brightness: Double = 1.0)

    @Serializable
    private data class InputDto(
        val source: InputSource = InputSource.PWM,
        val speedMinKmh: Double = 0.0,
        val speedMaxKmh: Double = InputConfig.DEFAULT_SPEED_MAX_KMH,
    )

    @Serializable
    private data class BrightnessDto(val master: Double = 1.0, val max: Double = 1.0)

    /** `staggerDelayMs` absent means synchronised. */
    @Serializable
    private data class SyncDto(val staggerDelayMs: Long? = null)

    @Serializable
    private data class SafetyDto(
        val pwmPercent: Double? = SafetyConfig.DEFAULT_PWM_PERCENT,
        val speedKmh: Double? = null,
        val temperatureC: Double? = null,
        val currentA: Double? = null,
        val voltageBelowV: Double? = null,
        val holdMs: Long = 1_500,
    )

    @Serializable
    private data class ManualDto(
        val power: Boolean = true,
        val mode: LightMode = LightMode.STATIC,
        val color: String = "#FFFFFF",
        val level: Int = 255,
        val effect: Int = 1,
        val effectSpeed: Int = 5,
        val effectLength: Int = 10,
    )

    @Serializable
    private data class BoardDto(val id: String, val name: String, val address: String, val protocol: String)

    private fun LedSettings.toFile() = SettingsFile(
        mode = mode,
        pattern = engine.pattern.toDto(),
        input = InputDto(engine.input.source, engine.input.speedMinKmh, engine.input.speedMaxKmh),
        brightness = BrightnessDto(engine.brightness.master, engine.brightness.max),
        sync = SyncDto((engine.sync as? SyncMode.Staggered)?.delayMs),
        criticalStyle = engine.criticalStyle,
        safety = SafetyDto(
            safety.pwmPercent, safety.speedKmh, safety.temperatureC, safety.currentA, safety.voltageBelowV, safety.holdMs,
        ),
        manual = ManualDto(
            manual.power, manual.mode, manual.color.toHex(), manual.level, manual.effect, manual.effectSpeed, manual.effectLength,
        ),
        boards = boards.map { BoardDto(it.id, it.name, it.address, it.protocolId) },
        smoothingMs = smoothingMs,
        telemetryLoss = telemetryLoss,
        telemetryTimeoutMs = telemetryTimeoutMs,
    )

    private fun SettingsFile.toSettings() = LedSettings(
        mode = mode,
        engine = EngineConfig(
            pattern = pattern.toPattern(),
            input = InputConfig(input.source, input.speedMinKmh, input.speedMaxKmh),
            brightness = BrightnessConfig(brightness.master, brightness.max),
            sync = sync.staggerDelayMs?.let { SyncMode.Staggered(it) } ?: SyncMode.Synchronized,
            criticalStyle = criticalStyle,
        ),
        safety = SafetyConfig(
            safety.pwmPercent, safety.speedKmh, safety.temperatureC, safety.currentA, safety.voltageBelowV, safety.holdMs,
        ),
        manual = ManualLight(
            manual.power, manual.mode, Rgb.fromHex(manual.color), manual.level, manual.effect, manual.effectSpeed, manual.effectLength,
        ),
        boards = boards.map { BoardInfo(it.id, it.name, it.address, it.protocol) },
        smoothingMs = smoothingMs,
        telemetryLoss = telemetryLoss,
        telemetryTimeoutMs = telemetryTimeoutMs,
    )

    private fun Pattern.toDto(): PatternDto = when (this) {
        Pattern.PwmSpectrum -> PatternDto.PwmSpectrum
        is Pattern.CustomSpectrum -> PatternDto.CustomSpectrum(colors.map { it.toHex() })
        is Pattern.SpeedBlink -> PatternDto.SpeedBlink(first.toHex(), second.toHex(), maxHz)
        Pattern.ContinuousGradient -> PatternDto.ContinuousGradient
        is Pattern.ThreeColorGradient -> PatternDto.ThreeColorGradient(start.toHex(), middle.toHex(), end.toHex(), midpoint)
        is Pattern.Pulse -> PatternDto.Pulse(
            items = items.map {
                PulseItemDto(
                    it.color.toHex(), it.frequencyHz, it.rangeStartPercent, it.rangeEndPercent,
                    it.shape, it.minBrightness, it.maxBrightness, it.dutyCycle,
                )
            },
            idle = when (val light = idle) {
                IdleLight.Off -> null
                is IdleLight.Steady -> IdleDto(light.color.toHex(), light.brightness)
            },
        )
        is Pattern.ColorSequence -> PatternDto.ColorSequence(colors.map { it.toHex() }, smooth)
    }

    private fun PatternDto.toPattern(): Pattern = when (this) {
        PatternDto.PwmSpectrum -> Pattern.PwmSpectrum
        is PatternDto.CustomSpectrum -> Pattern.CustomSpectrum(colors.map(Rgb::fromHex))
        is PatternDto.SpeedBlink -> Pattern.SpeedBlink(Rgb.fromHex(first), Rgb.fromHex(second), maxHz)
        PatternDto.ContinuousGradient -> Pattern.ContinuousGradient
        is PatternDto.ThreeColorGradient ->
            Pattern.ThreeColorGradient(Rgb.fromHex(start), Rgb.fromHex(middle), Rgb.fromHex(end), midpoint)
        is PatternDto.Pulse -> Pattern.Pulse(
            items = items.map {
                PulseItem(
                    Rgb.fromHex(it.color), it.frequencyHz, it.rangeStartPercent, it.rangeEndPercent,
                    it.shape, it.minBrightness, it.maxBrightness, it.dutyCycle,
                )
            },
            idle = idle?.let { IdleLight.Steady(Rgb.fromHex(it.color), it.brightness) } ?: IdleLight.Off,
        )
        is PatternDto.ColorSequence -> Pattern.ColorSequence(colors.map(Rgb::fromHex), smooth)
    }
}
