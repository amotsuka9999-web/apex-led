package app.apexled.pattern

/**
 * A colour pattern. Each one maps a position `t` in 0..1 (the normalised driving input)
 * and a time to a colour and an intensity. See docs/padroes-cor.md for the exact mappings.
 */
sealed interface Pattern {

    /**
     * P1 - full spectrum, cold to hot: t=0 is blue, t=1 is red, passing through
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
