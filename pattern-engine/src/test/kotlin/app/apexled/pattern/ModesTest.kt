package app.apexled.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Modes 1, 2 and 3 of the revised specification. */
class ModesTest {

    private fun at(pattern: Pattern, pwm: Double) =
        PatternEngine.frame(WheelSample(0.0, pwm), false, EngineConfig(pattern = pattern), 0).color

    private val orange = Rgb(255, 128, 0)

    @Test
    fun `mode 1 hits blue green yellow orange red at 0 24 48 72 96 percent`() {
        assertEquals(Rgb.BLUE, at(Pattern.PwmSpectrum, 0.0))
        assertEquals(Rgb.GREEN, at(Pattern.PwmSpectrum, 24.0))
        assertEquals(Rgb.YELLOW, at(Pattern.PwmSpectrum, 48.0))
        assertEquals(orange, at(Pattern.PwmSpectrum, 72.0))
        assertEquals(Rgb.RED, at(Pattern.PwmSpectrum, 96.0))
    }

    @Test
    fun `mode 1 interpolates linearly in RGB between stops`() {
        assertEquals(Rgb.lerp(Rgb.BLUE, Rgb.GREEN, 0.5), at(Pattern.PwmSpectrum, 12.0))
        assertEquals(Rgb.lerp(Rgb.GREEN, Rgb.YELLOW, 0.5), at(Pattern.PwmSpectrum, 36.0))
        assertEquals(Rgb.lerp(Rgb.YELLOW, orange, 0.25), at(Pattern.PwmSpectrum, 54.0))
        assertEquals(Rgb.lerp(orange, Rgb.RED, 0.5), at(Pattern.PwmSpectrum, 84.0))
    }

    @Test
    fun `mode 1 is continuous across every stop and stays red above 96`() {
        var previous = at(Pattern.PwmSpectrum, 0.0)
        for (step in 1..1000) {
            val current = at(Pattern.PwmSpectrum, step / 10.0)
            val jump = maxOf(
                kotlin.math.abs(current.r - previous.r),
                kotlin.math.abs(current.g - previous.g),
                kotlin.math.abs(current.b - previous.b),
            )
            assertTrue(jump <= 2, "jump of $jump at ${step / 10.0} %")
            previous = current
        }
        assertEquals(Rgb.RED, at(Pattern.PwmSpectrum, 98.0))
        assertEquals(Rgb.RED, at(Pattern.PwmSpectrum, 100.0))
    }

    @Test
    fun `mode 2 uses the five chosen colours at the same stops`() {
        val colors = listOf(Rgb(10, 0, 0), Rgb(0, 20, 0), Rgb(0, 0, 30), Rgb(40, 40, 0), Rgb(0, 50, 50))
        val custom = Pattern.CustomSpectrum(colors)
        listOf(0.0, 24.0, 48.0, 72.0, 96.0).forEachIndexed { index, pwm -> assertEquals(colors[index], at(custom, pwm)) }
        assertEquals(Rgb.lerp(colors[1], colors[2], 0.5), at(custom, 36.0))
        assertEquals(colors[4], at(custom, 100.0))
        assertEquals(PatternEngine.preview(Pattern.PwmSpectrum, 26), PatternEngine.preview(Pattern.CustomSpectrum(), 26))
        assertFailsWith<IllegalArgumentException> { Pattern.CustomSpectrum(colors.take(4)) }
    }

    private val blink = Pattern.SpeedBlink(Rgb.GREEN, Rgb.BLUE, maxHz = 5.0)
    private val bySpeed = EngineConfig(pattern = blink, input = InputConfig(InputSource.SPEED, speedMaxKmh = 50.0))

    private fun blinkAt(speed: Double, timeMs: Long) = PatternEngine.frame(WheelSample(speed, 0.0), false, bySpeed, timeMs).color

    private fun togglesPerSecond(speed: Double): Int {
        var toggles = 0
        var previous = blinkAt(speed, 0)
        for (timeMs in 1L..1000L) {
            val current = blinkAt(speed, timeMs)
            if (current != previous) toggles++
            previous = current
        }
        return toggles
    }

    @Test
    fun `mode 3 holds the first colour at zero speed`() {
        for (timeMs in 0L..3000L step 13) assertEquals(Rgb.GREEN, blinkAt(0.0, timeMs))
    }

    @Test
    fun `mode 3 alternates faster with speed up to the frequency limit`() {
        // A full cycle is two colour changes: f Hz gives 2f changes a second.
        assertEquals(2, togglesPerSecond(10.0))
        assertEquals(5, togglesPerSecond(25.0))
        assertEquals(10, togglesPerSecond(50.0))
        assertEquals(10, togglesPerSecond(90.0), "never above the limit, even beyond the speed range")
    }

    @Test
    fun `mode 3 frequency limit is configurable and bounded`() {
        val slow = bySpeed.copy(pattern = blink.copy(maxHz = 2.0))
        var toggles = 0
        var previous = PatternEngine.frame(WheelSample(50.0, 0.0), false, slow, 0).color
        for (timeMs in 1L..1000L) {
            val current = PatternEngine.frame(WheelSample(50.0, 0.0), false, slow, timeMs).color
            if (current != previous) toggles++
            previous = current
        }
        assertEquals(4, toggles)
        assertEquals(5.0, Pattern.SpeedBlink().maxHz)
        assertFailsWith<IllegalArgumentException> { Pattern.SpeedBlink(maxHz = 10.5) }
        assertFailsWith<IllegalArgumentException> { Pattern.SpeedBlink(maxHz = 0.0) }
    }

    @Test
    fun `critical overrides the new modes`() {
        for (pattern in listOf(Pattern.PwmSpectrum, Pattern.CustomSpectrum(), blink)) {
            val frame = PatternEngine.frame(WheelSample(30.0, 50.0), true, EngineConfig(pattern = pattern), 5)
            assertEquals(Rgb.RED, frame.color)
            assertTrue(frame.critical)
        }
    }
}
