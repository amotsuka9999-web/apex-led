package app.apexled.pattern

/**
 * A colour pattern. Each one maps a position `t` in 0..1 (the normalised driving input)
 * and a time to a colour and an intensity. See docs/padroes-cor.md for the exact mappings.
 */
sealed interface Pattern {

    /**
     * Mode 1 - spectrum by PWM: blue, green, yellow, orange, red at 0, 24, 48, 72 and 96 %,
     * blended linearly in RGB between neighbours. From 96 % up it stays on the last colour.
     */
    data object PwmSpectrum : Pattern

    /** Mode 2 - same five stops as [PwmSpectrum], each with a colour chosen by the user. */
    data class CustomSpectrum(val colors: List<Rgb> = SPECTRUM_DEFAULT) : Pattern {
        init {
            require(colors.size == SPECTRUM_STOPS.size) { "CustomSpectrum needs ${SPECTRUM_STOPS.size} colours" }
        }
    }

    /**
     * Mode 3 - alternates between two colours, faster as the input (speed by default) grows.
     * Frequency = [maxHz] x position. At position zero it stays on [first].
     * [maxHz] is capped at [MAX_HZ_LIMIT]: fast alternation can cause discomfort or trigger
     * photosensitive epilepsy.
     */
    data class SpeedBlink(val first: Rgb = Rgb.BLUE, val second: Rgb = Rgb.WHITE, val maxHz: Double = DEFAULT_MAX_HZ) : Pattern {
        init {
            require(maxHz in MIN_HZ_LIMIT..MAX_HZ_LIMIT) { "maxHz must be $MIN_HZ_LIMIT..$MAX_HZ_LIMIT" }
        }

        companion object {
            const val DEFAULT_MAX_HZ = 5.0
            const val MIN_HZ_LIMIT = 0.5
            const val MAX_HZ_LIMIT = 10.0
        }
    }

    /**
     * P1 (earlier draft) - full spectrum, cold to hot: t=0 is blue, t=1 is red, passing through
     * cyan, green and yellow. Hue = 240 * (1 - t) degrees.
     */
    data object ContinuousGradient : Pattern

    /**
     * P2 - three user colours. `t` in 0..[midpoint] blends [start] into [middle],
     * [midpoint]..1 blends [middle] into [end].
     */
    data class ThreeColorGradient(
        val start: Rgb = Rgb.GREEN,
        val middle: Rgb = Rgb.YELLOW,
        val end: Rgb = Rgb.RED,
        val midpoint: Double = 0.5,
    ) : Pattern {
        init {
            require(midpoint > 0.0 && midpoint < 1.0) { "midpoint must be strictly between 0 and 1" }
        }
    }

    /**
     * P3 - pulse / intensity. The first item whose range contains the input is shown;
     * outside every range the light shows [idle] (dark by default).
     */
    data class Pulse(
        val items: List<PulseItem> = listOf(PulseItem()),
        val idle: IdleLight = IdleLight.Off,
    ) : Pattern {
        init {
            require(items.isNotEmpty()) { "Pulse needs at least one item" }
            require(items.size <= MAX_ITEMS) { "Pulse supports at most $MAX_ITEMS items" }
        }

        companion object {
            const val MAX_ITEMS = 8
        }
    }

    /**
     * Transition mode - PROPOSAL, awaiting confirmation (docs/questoes.md, Q4).
     * [colors] are spread evenly over 0..1; with [smooth] the input blends between neighbours,
     * without it the colour steps at each boundary.
     */
    data class ColorSequence(
        val colors: List<Rgb> = listOf(Rgb.GREEN, Rgb.RED, Rgb.GREEN, Rgb.BLUE),
        val smooth: Boolean = true,
    ) : Pattern {
        init {
            require(colors.size in 2..MAX_COLORS) { "ColorSequence needs 2..$MAX_COLORS colours" }
        }

        companion object {
            const val MAX_COLORS = 12
        }
    }
}

/** Positions (0..1) of the five stops of modes 1 and 2: 0, 24, 48, 72 and 96 %. */
val SPECTRUM_STOPS = listOf(0.0, 0.24, 0.48, 0.72, 0.96)

/** Mode 1 colours: blue, green, yellow, orange, red. */
val SPECTRUM_DEFAULT = listOf(Rgb.BLUE, Rgb.GREEN, Rgb.YELLOW, Rgb(255, 128, 0), Rgb.RED)

/** What a [Pattern.Pulse] shows when the input is outside every item's range. */
sealed interface IdleLight {
    data object Off : IdleLight

    data class Steady(val color: Rgb, val brightness: Double = 1.0) : IdleLight {
        init {
            require(brightness in 0.0..1.0) { "brightness must be 0..1" }
        }
    }
}

enum class PulseShape {
    /** Square wave: [PulseItem.maxBrightness] for the duty part of each period, [PulseItem.minBrightness] for the rest. */
    BLINK,

    /** Sine wave between [PulseItem.minBrightness] and [PulseItem.maxBrightness]. */
    BREATHE,
}

/**
 * One configurable pulse.
 *
 * @param frequencyHz pulses per second, [MIN_HZ]..[MAX_HZ]. The ceiling keeps flashing at or
 *   below three per second (photosensitivity guidance) and within what a BLE link delivers reliably.
 * @param rangeStartPercent start of the active range, in percent of the normalised input (0..100).
 * @param rangeEndPercent end of the active range, inclusive. Must not be below the start.
 * @param dutyCycle fraction of each period spent at [maxBrightness] when [shape] is BLINK.
 */
data class PulseItem(
    val color: Rgb = Rgb.WHITE,
    val frequencyHz: Double = 1.0,
    val rangeStartPercent: Double = 0.0,
    val rangeEndPercent: Double = 100.0,
    val shape: PulseShape = PulseShape.BLINK,
    val minBrightness: Double = 0.0,
    val maxBrightness: Double = 1.0,
    val dutyCycle: Double = 0.5,
) {
    init {
        require(frequencyHz in MIN_HZ..MAX_HZ) { "frequencyHz must be $MIN_HZ..$MAX_HZ" }
        require(rangeStartPercent in 0.0..100.0 && rangeEndPercent in 0.0..100.0) { "range must be within 0..100" }
        require(rangeEndPercent >= rangeStartPercent) { "rangeEndPercent must not be below rangeStartPercent" }
        require(minBrightness in 0.0..1.0 && maxBrightness in 0.0..1.0) { "brightness must be 0..1" }
        require(maxBrightness >= minBrightness) { "maxBrightness must not be below minBrightness" }
        require(dutyCycle > 0.0 && dutyCycle < 1.0) { "dutyCycle must be strictly between 0 and 1" }
    }

    companion object {
        const val MIN_HZ = 0.1
        const val MAX_HZ = 3.0
    }
}
