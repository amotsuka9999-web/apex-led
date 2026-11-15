package app.apexled.pattern

/** Which wheel reading drives the pattern. Chosen by the user. */
enum class InputSource { SPEED, PWM }

/**
 * One telemetry reading, already converted to display units.
 *
 * @param speedKmh wheel speed in km/h. Sign is ignored (riding backwards looks like riding forwards).
 * @param pwmPercent motor PWM / duty in percent, nominally 0..100. Sign is ignored.
 */
data class WheelSample(val speedKmh: Double, val pwmPercent: Double) {
    companion object {
        val ZERO = WheelSample(0.0, 0.0)
    }
}

/**
 * What one board should show at one instant.
 *
 * @param color hue of the light at full intensity.
 * @param brightness 0.0 (dark) .. 1.0 (full), already limited by [BrightnessConfig].
 * @param critical true when this frame is the critical-alert indicator rather than a cosmetic pattern.
 */
data class LedFrame(val color: Rgb, val brightness: Double, val critical: Boolean = false) {
    init {
        require(brightness in 0.0..1.0) { "brightness must be 0..1, got $brightness" }
    }

    /** Brightness as the 0..255 level most controllers take. */
    val level: Int get() = Math.round(brightness * 255.0).toInt()
}

/** How the driving input is turned into a 0..1 position along the pattern. */
data class InputConfig(
    val source: InputSource = InputSource.PWM,
    /** Speed mapped to position 0 when [source] is SPEED. */
    val speedMinKmh: Double = 0.0,
    /** Speed mapped to position 1 when [source] is SPEED. Must be above [speedMinKmh]. */
    val speedMaxKmh: Double = DEFAULT_SPEED_MAX_KMH,
) {
    init {
        require(speedMinKmh >= 0.0) { "speedMinKmh must be >= 0" }
        require(speedMaxKmh > speedMinKmh) { "speedMaxKmh must be above speedMinKmh" }
    }

    companion object {
        /** Provisional default; the right value depends on the wheel (docs/questoes.md, Q2). */
        const val DEFAULT_SPEED_MAX_KMH = 50.0
    }
}

/**
 * Brightness limits applied to every frame.
 *
 * @param master user-facing brightness, 0..1. Scales cosmetic patterns only.
 * @param max hard ceiling, [MIN_MAX_BRIGHTNESS]..1. Nothing the engine emits ever exceeds it,
 *   including the critical indicator. It cannot be set to zero, so the ceiling cannot be used
 *   to hide the critical indicator.
 */
data class BrightnessConfig(val master: Double = 1.0, val max: Double = 1.0) {
    init {
        require(master in 0.0..1.0) { "master must be 0..1" }
        require(max in MIN_MAX_BRIGHTNESS..1.0) { "max must be $MIN_MAX_BRIGHTNESS..1" }
    }

    companion object {
        const val MIN_MAX_BRIGHTNESS = 0.05
    }
}

/** How several boards relate to each other in time. */
sealed interface SyncMode {
    /** Every board shows the same frame. */
    data object Synchronized : SyncMode

    /** Board `i` shows what board 0 showed `i * delayMs` ago. */
    data class Staggered(val delayMs: Long = 150) : SyncMode {
        init {
            require(delayMs in 0..MAX_DELAY_MS) { "delayMs must be 0..$MAX_DELAY_MS" }
        }

        companion object {
            const val MAX_DELAY_MS = 2_000L
        }
    }
}

/** How the critical indicator looks. Solid red is what the requirements ask for. */
enum class CriticalStyle {
    SOLID,

    /** Red flashing at [PatternEngine.CRITICAL_FLASH_HZ], for setups where a pattern also uses red. */
    FLASH,
}

/** Everything the engine needs besides telemetry and time. Identical for every board. */
data class EngineConfig(
    val pattern: Pattern = Pattern.ContinuousGradient,
    val input: InputConfig = InputConfig(),
    val brightness: BrightnessConfig = BrightnessConfig(),
    val sync: SyncMode = SyncMode.Synchronized,
    val criticalStyle: CriticalStyle = CriticalStyle.SOLID,
)
