package app.apexled.pattern

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * The pattern engine: a pure function from (speed, PWM, critical alert, configuration, time)
 * to a colour and brightness per board. No I/O, no clock, no mutable state - the same
 * arguments always give the same frames, which is what makes the mapping testable and predictable.
 *
 * Safety contract (covered by PatternEngineSafetyTest):
 *  - when `critical` is true every board gets the critical indicator, whatever the pattern says;
 *  - the critical indicator is never staggered: no board waits for its delay to show it;
 *  - no frame is ever brighter than [BrightnessConfig.max].
 */
object PatternEngine {

    const val MAX_BOARDS = 10

    val CRITICAL_COLOR = Rgb.RED

    /** Flash rate of [CriticalStyle.FLASH]. Two per second: clearly urgent, below the three-per-second limit. */
    const val CRITICAL_FLASH_HZ = 2.0

    /** Dim phase of [CriticalStyle.FLASH], as a fraction of the lit phase. Never fully dark. */
    const val CRITICAL_FLASH_LOW = 0.25

    /**
     * Normalises the driving input to a position in 0..1.
     * PWM: |pwm| / 100. Speed: (|speed| - min) / (max - min). Out-of-range values clamp; NaN counts as 0.
     */
    fun position(sample: WheelSample, input: InputConfig): Double {
        val raw = when (input.source) {
            InputSource.PWM -> abs(sample.pwmPercent) / 100.0
            InputSource.SPEED -> (abs(sample.speedKmh) - input.speedMinKmh) / (input.speedMaxKmh - input.speedMinKmh)
        }
        return if (raw.isNaN()) 0.0 else raw.coerceIn(0.0, 1.0)
    }

    /** The frame for a single board. */
    fun frame(sample: WheelSample, critical: Boolean, config: EngineConfig, timeMs: Long): LedFrame {
        if (critical) return criticalFrame(config, timeMs)
        val t = position(sample, config.input)
        val (color, intensity) = evaluate(config.pattern, t, timeMs)
        return LedFrame(color, limit(intensity * config.brightness.master, config.brightness))
    }

    /**
     * The frames for [boardCount] boards, index 0 first.
     *
     * With [SyncMode.Staggered], board `i` is evaluated on the telemetry and the clock of
     * `timeMs - i * delayMs`, so it replays board 0 with a delay. [critical] always refers to
     * "now" and applies to every board at once.
     */
    fun frames(
        history: TelemetryHistory,
        critical: Boolean,
        config: EngineConfig,
        boardCount: Int,
        timeMs: Long,
    ): List<LedFrame> {
        require(boardCount in 0..MAX_BOARDS) { "boardCount must be 0..$MAX_BOARDS, got $boardCount" }
        if (critical) {
            val frame = criticalFrame(config, timeMs)
            return List(boardCount) { frame }
        }
        return when (val sync = config.sync) {
            SyncMode.Synchronized -> {
                val frame = frame(history.sampleAt(timeMs), false, config, timeMs)
                List(boardCount) { frame }
            }
            is SyncMode.Staggered -> List(boardCount) { index ->
                val boardTime = timeMs - index * sync.delayMs
                frame(history.sampleAt(boardTime), false, config, boardTime)
            }
        }
    }

    /** The critical indicator: red at the brightness ceiling. Ignores pattern and master brightness. */
    fun criticalFrame(config: EngineConfig, timeMs: Long): LedFrame {
        val ceiling = config.brightness.max
        val brightness = when (config.criticalStyle) {
            CriticalStyle.SOLID -> ceiling
            CriticalStyle.FLASH -> if (phase(timeMs, CRITICAL_FLASH_HZ) < 0.5) ceiling else ceiling * CRITICAL_FLASH_LOW
        }
        return LedFrame(CRITICAL_COLOR, brightness, critical = true)
    }

    /**
     * Colours of the pattern at [steps] evenly spaced positions from 0 to 1, for the preview strip.
     * For [Pattern.Pulse] it shows each item's colour over its range (the pulsing itself is time-based).
     */
    fun preview(pattern: Pattern, steps: Int): List<Rgb> {
        require(steps >= 2) { "steps must be at least 2" }
        return List(steps) { index ->
            val t = index.toDouble() / (steps - 1)
            when (pattern) {
                is Pattern.Pulse -> when (val item = activeItem(pattern, t)) {
                    null -> when (val idle = pattern.idle) {
                        IdleLight.Off -> Rgb.BLACK
                        is IdleLight.Steady -> idle.color
                    }
                    else -> item.color
                }
                is Pattern.SpeedBlink -> if (index % 2 == 0) pattern.first else pattern.second
                else -> evaluate(pattern, t, 0L).first
            }
        }
    }

    /** Pattern colour and unlimited intensity (0..1) at position [t] and time [timeMs]. */
    internal fun evaluate(pattern: Pattern, t: Double, timeMs: Long): Pair<Rgb, Double> = when (pattern) {
        Pattern.PwmSpectrum -> spectrumColor(SPECTRUM_DEFAULT, t) to 1.0

        is Pattern.CustomSpectrum -> spectrumColor(pattern.colors, t) to 1.0

        is Pattern.SpeedBlink -> {
            val hz = pattern.maxHz * t
            (if (hz <= 0.0 || phase(timeMs, hz) < 0.5) pattern.first else pattern.second) to 1.0
        }

        Pattern.ContinuousGradient -> Rgb.fromHue(COLD_HUE * (1.0 - t)) to 1.0

        is Pattern.ThreeColorGradient ->
            if (t <= pattern.midpoint) {
                Rgb.lerp(pattern.start, pattern.middle, t / pattern.midpoint) to 1.0
            } else {
                Rgb.lerp(pattern.middle, pattern.end, (t - pattern.midpoint) / (1.0 - pattern.midpoint)) to 1.0
            }

        is Pattern.ColorSequence -> sequenceColor(pattern, t) to 1.0

        is Pattern.Pulse -> when (val item = activeItem(pattern, t)) {
            null -> when (val idle = pattern.idle) {
                IdleLight.Off -> Rgb.BLACK to 0.0
                is IdleLight.Steady -> idle.color to idle.brightness
            }
            else -> item.color to pulseIntensity(item, timeMs)
        }
    }

    private fun spectrumColor(colors: List<Rgb>, t: Double): Rgb {
        val last = SPECTRUM_STOPS.lastIndex
        if (t >= SPECTRUM_STOPS[last]) return colors[last]
        val index = SPECTRUM_STOPS.indexOfLast { it <= t }.coerceIn(0, last - 1)
        val span = SPECTRUM_STOPS[index + 1] - SPECTRUM_STOPS[index]
        return Rgb.lerp(colors[index], colors[index + 1], (t - SPECTRUM_STOPS[index]) / span)
    }

    private fun activeItem(pattern: Pattern.Pulse, t: Double): PulseItem? {
        val percent = t * 100.0
        return pattern.items.firstOrNull { percent >= it.rangeStartPercent && percent <= it.rangeEndPercent }
    }

    private fun pulseIntensity(item: PulseItem, timeMs: Long): Double {
        val phase = phase(timeMs, item.frequencyHz)
        return when (item.shape) {
            PulseShape.BLINK -> if (phase < item.dutyCycle) item.maxBrightness else item.minBrightness
            // Starts at the minimum, peaks mid-period, returns to the minimum.
            PulseShape.BREATHE -> {
                val wave = (1.0 - cos(2.0 * PI * phase)) / 2.0
                item.minBrightness + (item.maxBrightness - item.minBrightness) * wave
            }
        }
    }

    private fun sequenceColor(pattern: Pattern.ColorSequence, t: Double): Rgb {
        val colors = pattern.colors
        if (pattern.smooth) {
            val scaled = t * (colors.size - 1)
            val index = min(scaled.toInt(), colors.size - 2)
            return Rgb.lerp(colors[index], colors[index + 1], scaled - index)
        }
        return colors[min((t * colors.size).toInt(), colors.size - 1)]
    }

    /** Position within the current period, 0 (inclusive) .. 1 (exclusive). Well defined for negative times too. */
    private fun phase(timeMs: Long, frequencyHz: Double): Double {
        val cycles = timeMs / 1000.0 * frequencyHz
        val fraction = cycles - Math.floor(cycles)
        return if (fraction >= 1.0) 0.0 else fraction
    }

    private fun limit(brightness: Double, config: BrightnessConfig): Double =
        brightness.coerceIn(0.0, config.max)

    private const val COLD_HUE = 240.0
}
