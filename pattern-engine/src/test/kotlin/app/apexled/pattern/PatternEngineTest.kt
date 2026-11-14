package app.apexled.pattern

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatternEngineTest {

    private fun pwm(percent: Double) = WheelSample(speedKmh = 0.0, pwmPercent = percent)
    private fun speed(kmh: Double) = WheelSample(speedKmh = kmh, pwmPercent = 0.0)

    private fun colorAt(pattern: Pattern, pwmPercent: Double, timeMs: Long = 0): Rgb =
        PatternEngine.frame(pwm(pwmPercent), false, EngineConfig(pattern = pattern), timeMs).color

    // ---- input normalisation -------------------------------------------------------------

    @Test
    fun `pwm position spans 0 to 1 and clamps`() {
        val input = InputConfig(source = InputSource.PWM)
        assertEquals(0.0, PatternEngine.position(pwm(0.0), input))
        assertEquals(0.5, PatternEngine.position(pwm(50.0), input))
        assertEquals(1.0, PatternEngine.position(pwm(100.0), input))
        assertEquals(1.0, PatternEngine.position(pwm(140.0), input))
        assertEquals(0.37, PatternEngine.position(pwm(-37.0), input), 1e-9)
    }

    @Test
    fun `speed position uses the configured range and ignores direction`() {
        val input = InputConfig(source = InputSource.SPEED, speedMinKmh = 10.0, speedMaxKmh = 60.0)
        assertEquals(0.0, PatternEngine.position(speed(0.0), input))
        assertEquals(0.0, PatternEngine.position(speed(10.0), input))
        assertEquals(0.5, PatternEngine.position(speed(35.0), input))
        assertEquals(1.0, PatternEngine.position(speed(60.0), input))
        assertEquals(1.0, PatternEngine.position(speed(95.0), input))
        assertEquals(0.5, PatternEngine.position(speed(-35.0), input))
    }

    @Test
    fun `the selected source is the only one that matters`() {
        val sample = WheelSample(speedKmh = 50.0, pwmPercent = 20.0)
        assertEquals(0.2, PatternEngine.position(sample, InputConfig(source = InputSource.PWM)), 1e-9)
        assertEquals(1.0, PatternEngine.position(sample, InputConfig(source = InputSource.SPEED, speedMaxKmh = 50.0)))
    }

    @Test
    fun `invalid telemetry counts as zero`() {
        assertEquals(0.0, PatternEngine.position(pwm(Double.NaN), InputConfig()))
        assertEquals(1.0, PatternEngine.position(pwm(Double.POSITIVE_INFINITY), InputConfig()))
        assertEquals(0.0, PatternEngine.position(speed(Double.NaN), InputConfig(source = InputSource.SPEED)))
    }

    // ---- P1 continuous gradient ----------------------------------------------------------

    @Test
    fun `P1 goes from blue at 0 to red at 100 through cyan green and yellow`() {
        val p1 = Pattern.ContinuousGradient
        assertEquals(Rgb(0, 0, 255), colorAt(p1, 0.0))
        assertEquals(Rgb(0, 255, 255), colorAt(p1, 25.0))
        assertEquals(Rgb(0, 255, 0), colorAt(p1, 50.0))
        assertEquals(Rgb(255, 255, 0), colorAt(p1, 75.0))
        assertEquals(Rgb(255, 0, 0), colorAt(p1, 100.0))
    }

    @Test
    fun `P1 is smooth - one percent of PWM never moves a channel more than 11 steps`() {
        var previous = colorAt(Pattern.ContinuousGradient, 0.0)
        for (step in 1..1000) {
            val current = colorAt(Pattern.ContinuousGradient, step / 10.0)
            val jump = maxOf(abs(current.r - previous.r), abs(current.g - previous.g), abs(current.b - previous.b))
            // 0.1 % of PWM is 0.24 degrees of hue, i.e. about one 8-bit step.
            assertTrue(jump <= 2, "jump of $jump at ${step / 10.0} %")
            previous = current
        }
    }

    @Test
    fun `P1 never passes through magenta or goes dark`() {
        for (step in 0..100) {
            val color = colorAt(Pattern.ContinuousGradient, step.toDouble())
            assertEquals(255, maxOf(color.r, color.g, color.b), "full value at $step %")
            assertTrue(color.r == 0 || color.b == 0, "red and blue never mix at $step % ($color)")
        }
    }

    @Test
    fun `P1 clamps outside 0 to 100`() {
        assertEquals(Rgb.BLUE, colorAt(Pattern.ContinuousGradient, -0.0))
        assertEquals(Rgb.RED, colorAt(Pattern.ContinuousGradient, 250.0))
    }

    // ---- P2 three colour gradient --------------------------------------------------------

    private val p2 = Pattern.ThreeColorGradient(start = Rgb(0, 0, 200), middle = Rgb(100, 200, 0), end = Rgb(200, 0, 100))

    @Test
    fun `P2 hits the three chosen colours at 0, the midpoint and 100`() {
        assertEquals(p2.start, colorAt(p2, 0.0))
        assertEquals(p2.middle, colorAt(p2, 50.0))
        assertEquals(p2.end, colorAt(p2, 100.0))
    }

    @Test
    fun `P2 interpolates linearly on each side of the midpoint`() {
        assertEquals(Rgb(50, 100, 100), colorAt(p2, 25.0))
        assertEquals(Rgb(150, 100, 50), colorAt(p2, 75.0))
    }

    @Test
    fun `P2 is continuous across the midpoint`() {
        val below = colorAt(p2, 49.999)
        val above = colorAt(p2, 50.001)
        assertEquals(p2.middle, below)
        assertEquals(p2.middle, above)
    }

    @Test
    fun `P2 honours a custom midpoint`() {
        val early = p2.copy(midpoint = 0.2)
        assertEquals(p2.middle, colorAt(early, 20.0))
        assertEquals(Rgb.lerp(p2.start, p2.middle, 0.5), colorAt(early, 10.0))
        assertEquals(Rgb.lerp(p2.middle, p2.end, 0.5), colorAt(early, 60.0))
        assertFailsWith<IllegalArgumentException> { p2.copy(midpoint = 0.0) }
        assertFailsWith<IllegalArgumentException> { p2.copy(midpoint = 1.0) }
    }

    @Test
    fun `P2 with speed as input`() {
        val config = EngineConfig(pattern = p2, input = InputConfig(source = InputSource.SPEED, speedMaxKmh = 40.0))
        assertEquals(p2.start, PatternEngine.frame(speed(0.0), false, config, 0).color)
        assertEquals(p2.middle, PatternEngine.frame(speed(20.0), false, config, 0).color)
        assertEquals(p2.end, PatternEngine.frame(speed(40.0), false, config, 0).color)
    }

    // ---- P3 pulse ------------------------------------------------------------------------

    private fun frameAt(pattern: Pattern, pwmPercent: Double, timeMs: Long) =
        PatternEngine.frame(pwm(pwmPercent), false, EngineConfig(pattern = pattern), timeMs)

    @Test
    fun `P3 blink is a square wave at the configured frequency`() {
        val blink = Pattern.Pulse(listOf(PulseItem(color = Rgb.GREEN, frequencyHz = 2.0)))
        // 2 Hz: 500 ms period, lit for the first 250 ms.
        assertEquals(1.0, frameAt(blink, 50.0, 0).brightness)
        assertEquals(1.0, frameAt(blink, 50.0, 249).brightness)
        assertEquals(0.0, frameAt(blink, 50.0, 250).brightness)
        assertEquals(0.0, frameAt(blink, 50.0, 499).brightness)
        assertEquals(1.0, frameAt(blink, 50.0, 500).brightness)
        assertEquals(Rgb.GREEN, frameAt(blink, 50.0, 0).color)
    }

    @Test
    fun `P3 blink honours duty cycle and brightness bounds`() {
        val item = PulseItem(frequencyHz = 1.0, dutyCycle = 0.2, minBrightness = 0.1, maxBrightness = 0.8)
        val blink = Pattern.Pulse(listOf(item))
        assertEquals(0.8, frameAt(blink, 10.0, 199).brightness)
        assertEquals(0.1, frameAt(blink, 10.0, 200).brightness)
        assertEquals(0.1, frameAt(blink, 10.0, 999).brightness)
    }

    @Test
    fun `P3 breathe is a sine between min and max`() {
        val item = PulseItem(frequencyHz = 1.0, shape = PulseShape.BREATHE, minBrightness = 0.2, maxBrightness = 1.0)
        val breathe = Pattern.Pulse(listOf(item))
        assertEquals(0.2, frameAt(breathe, 0.0, 0).brightness, 1e-9)
        assertEquals(0.6, frameAt(breathe, 0.0, 250).brightness, 1e-9)
        assertEquals(1.0, frameAt(breathe, 0.0, 500).brightness, 1e-9)
        assertEquals(0.6, frameAt(breathe, 0.0, 750).brightness, 1e-9)
        assertEquals(0.2, frameAt(breathe, 0.0, 1000).brightness, 1e-9)
    }

    @Test
    fun `P3 breathe never leaves its bounds`() {
        val item = PulseItem(frequencyHz = 0.7, shape = PulseShape.BREATHE, minBrightness = 0.3, maxBrightness = 0.9)
        val breathe = Pattern.Pulse(listOf(item))
        for (timeMs in 0L..5000L step 7) {
            val brightness = frameAt(breathe, 0.0, timeMs).brightness
            assertTrue(brightness >= 0.3 - 1e-9 && brightness <= 0.9 + 1e-9, "brightness $brightness at $timeMs ms")
        }
    }

    @Test
    fun `P3 each item acts only inside its range and the range ends are inclusive`() {
        val low = PulseItem(color = Rgb.GREEN, rangeStartPercent = 0.0, rangeEndPercent = 40.0)
        val high = PulseItem(color = Rgb.YELLOW, frequencyHz = 3.0, rangeStartPercent = 70.0, rangeEndPercent = 100.0)
        val pulse = Pattern.Pulse(listOf(low, high))

        assertEquals(Rgb.GREEN, frameAt(pulse, 0.0, 0).color)
        assertEquals(Rgb.GREEN, frameAt(pulse, 40.0, 0).color)
        assertEquals(Rgb.YELLOW, frameAt(pulse, 70.0, 0).color)
        assertEquals(Rgb.YELLOW, frameAt(pulse, 100.0, 0).color)

        // Between the ranges nothing is active: dark by default.
        val gap = frameAt(pulse, 55.0, 0)
        assertEquals(0.0, gap.brightness)
    }

    @Test
    fun `P3 outside every range shows the idle light`() {
        val item = PulseItem(rangeStartPercent = 80.0, rangeEndPercent = 100.0)
        val steady = Pattern.Pulse(listOf(item), idle = IdleLight.Steady(Rgb.CYAN, 0.4))
        val frame = frameAt(steady, 10.0, 123)
        assertEquals(Rgb.CYAN, frame.color)
        assertEquals(0.4, frame.brightness)
    }

    @Test
    fun `P3 the first matching item wins when ranges overlap`() {
        val first = PulseItem(color = Rgb.GREEN, rangeStartPercent = 0.0, rangeEndPercent = 60.0)
        val second = PulseItem(color = Rgb.BLUE, rangeStartPercent = 40.0, rangeEndPercent = 100.0)
        val pulse = Pattern.Pulse(listOf(first, second))
        assertEquals(Rgb.GREEN, frameAt(pulse, 50.0, 0).color)
        assertEquals(Rgb.BLUE, frameAt(pulse, 61.0, 0).color)
    }

    @Test
    fun `P3 rejects out of range settings`() {
        assertFailsWith<IllegalArgumentException> { PulseItem(frequencyHz = 0.0) }
        assertFailsWith<IllegalArgumentException> { PulseItem(frequencyHz = PulseItem.MAX_HZ + 0.1) }
        assertFailsWith<IllegalArgumentException> { PulseItem(rangeStartPercent = 60.0, rangeEndPercent = 40.0) }
        assertFailsWith<IllegalArgumentException> { PulseItem(rangeEndPercent = 101.0) }
        assertFailsWith<IllegalArgumentException> { PulseItem(minBrightness = 0.9, maxBrightness = 0.5) }
        assertFailsWith<IllegalArgumentException> { PulseItem(dutyCycle = 1.0) }
        assertFailsWith<IllegalArgumentException> { Pattern.Pulse(emptyList()) }
    }

    // ---- transition mode (proposal) ------------------------------------------------------

    @Test
    fun `sequence blends through every colour in order`() {
        val sequence = Pattern.ColorSequence(listOf(Rgb.GREEN, Rgb.RED, Rgb.GREEN, Rgb.BLUE))
        assertEquals(Rgb.GREEN, colorAt(sequence, 0.0))
        assertEquals(Rgb.lerp(Rgb.GREEN, Rgb.RED, 0.5), colorAt(sequence, 100.0 / 6))
        assertEquals(Rgb.RED, colorAt(sequence, 100.0 / 3))
        assertEquals(Rgb.GREEN, colorAt(sequence, 200.0 / 3))
        assertEquals(Rgb.BLUE, colorAt(sequence, 100.0))
    }

    @Test
    fun `stepped sequence switches at equal bands`() {
        val sequence = Pattern.ColorSequence(listOf(Rgb.GREEN, Rgb.RED, Rgb.GREEN, Rgb.BLUE), smooth = false)
        assertEquals(Rgb.GREEN, colorAt(sequence, 0.0))
        assertEquals(Rgb.GREEN, colorAt(sequence, 24.9))
        assertEquals(Rgb.RED, colorAt(sequence, 25.0))
        assertEquals(Rgb.GREEN, colorAt(sequence, 50.0))
        assertEquals(Rgb.BLUE, colorAt(sequence, 75.0))
        assertEquals(Rgb.BLUE, colorAt(sequence, 100.0))
    }

    // ---- brightness ----------------------------------------------------------------------

    @Test
    fun `master brightness scales the pattern`() {
        val config = EngineConfig(brightness = BrightnessConfig(master = 0.4))
        assertEquals(0.4, PatternEngine.frame(pwm(50.0), false, config, 0).brightness, 1e-9)
    }

    @Test
    fun `brightness never exceeds the ceiling for any pattern input or time`() {
        val patterns = listOf(
            Pattern.ContinuousGradient,
            Pattern.ThreeColorGradient(),
            Pattern.Pulse(listOf(PulseItem(shape = PulseShape.BREATHE, frequencyHz = 3.0))),
            Pattern.Pulse(listOf(PulseItem(rangeEndPercent = 50.0)), idle = IdleLight.Steady(Rgb.WHITE, 1.0)),
            Pattern.ColorSequence(),
        )
        for (ceiling in listOf(BrightnessConfig.MIN_MAX_BRIGHTNESS, 0.3, 0.75, 1.0)) {
            for (pattern in patterns) {
                val config = EngineConfig(pattern = pattern, brightness = BrightnessConfig(master = 1.0, max = ceiling))
                for (percent in 0..100 step 5) {
                    for (timeMs in 0L..2000L step 37) {
                        for (critical in listOf(false, true)) {
                            val frame = PatternEngine.frame(pwm(percent.toDouble()), critical, config, timeMs)
                            assertTrue(frame.brightness <= ceiling, "$pattern at $percent % / $timeMs ms: ${frame.brightness} > $ceiling")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the ceiling clamps rather than rescales`() {
        val capped = BrightnessConfig(master = 0.5, max = 0.8)
        assertEquals(0.5, PatternEngine.frame(pwm(0.0), false, EngineConfig(brightness = capped), 0).brightness, 1e-9)
        val over = BrightnessConfig(master = 1.0, max = 0.8)
        assertEquals(0.8, PatternEngine.frame(pwm(0.0), false, EngineConfig(brightness = over), 0).brightness, 1e-9)
    }

    @Test
    fun `the ceiling cannot be configured to zero`() {
        assertFailsWith<IllegalArgumentException> { BrightnessConfig(max = 0.0) }
        assertFailsWith<IllegalArgumentException> { BrightnessConfig(max = 1.01) }
        assertFailsWith<IllegalArgumentException> { BrightnessConfig(master = -0.1) }
    }

    @Test
    fun `level maps brightness to 0 to 255`() {
        assertEquals(0, LedFrame(Rgb.RED, 0.0).level)
        assertEquals(128, LedFrame(Rgb.RED, 0.5).level)
        assertEquals(255, LedFrame(Rgb.RED, 1.0).level)
    }

    // ---- preview -------------------------------------------------------------------------

    @Test
    fun `preview spans the whole pattern`() {
        val strip = PatternEngine.preview(Pattern.ContinuousGradient, 5)
        assertEquals(listOf(Rgb.BLUE, Rgb.CYAN, Rgb.GREEN, Rgb.YELLOW, Rgb.RED), strip)
    }

    @Test
    fun `preview of a pulse shows item colours over their ranges`() {
        val pulse = Pattern.Pulse(
            listOf(PulseItem(color = Rgb.GREEN, rangeStartPercent = 0.0, rangeEndPercent = 25.0)),
            idle = IdleLight.Steady(Rgb.BLUE),
        )
        assertEquals(listOf(Rgb.GREEN, Rgb.GREEN, Rgb.BLUE, Rgb.BLUE, Rgb.BLUE), PatternEngine.preview(pulse, 5))
    }

    // ---- purity --------------------------------------------------------------------------

    @Test
    fun `same arguments give the same frame`() {
        val config = EngineConfig(pattern = Pattern.Pulse(listOf(PulseItem(shape = PulseShape.BREATHE))))
        val first = PatternEngine.frame(pwm(33.0), false, config, 123_456)
        repeat(5) { assertEquals(first, PatternEngine.frame(pwm(33.0), false, config, 123_456)) }
    }
}
