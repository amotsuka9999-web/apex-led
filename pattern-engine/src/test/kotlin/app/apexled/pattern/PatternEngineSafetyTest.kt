package app.apexled.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The critical indicator must win over everything the pattern settings can express. */
class PatternEngineSafetyTest {

    private val everyPattern = listOf(
        Pattern.ContinuousGradient,
        Pattern.ThreeColorGradient(start = Rgb.BLUE, middle = Rgb.BLUE, end = Rgb.BLUE),
        Pattern.Pulse(listOf(PulseItem(color = Rgb.GREEN, maxBrightness = 0.0, minBrightness = 0.0))),
        Pattern.Pulse(listOf(PulseItem(rangeStartPercent = 99.0, rangeEndPercent = 100.0)), idle = IdleLight.Off),
        Pattern.ColorSequence(listOf(Rgb.GREEN, Rgb.BLUE), smooth = false),
    )

    @Test
    fun `critical overrides every pattern with red`() {
        for (pattern in everyPattern) {
            for (percent in listOf(0.0, 50.0, 100.0)) {
                val frame = PatternEngine.frame(WheelSample(0.0, percent), true, EngineConfig(pattern = pattern), 777)
                assertEquals(Rgb.RED, frame.color, "pattern $pattern")
                assertTrue(frame.critical)
                assertEquals(1.0, frame.brightness)
            }
        }
    }

    @Test
    fun `critical ignores master brightness`() {
        val config = EngineConfig(brightness = BrightnessConfig(master = 0.0, max = 1.0))
        val cosmetic = PatternEngine.frame(WheelSample.ZERO, false, config, 0)
        val critical = PatternEngine.frame(WheelSample.ZERO, true, config, 0)
        assertEquals(0.0, cosmetic.brightness)
        assertEquals(1.0, critical.brightness)
    }

    @Test
    fun `critical is shown at the ceiling even at the lowest allowed ceiling`() {
        val config = EngineConfig(brightness = BrightnessConfig(master = 0.0, max = BrightnessConfig.MIN_MAX_BRIGHTNESS))
        val frame = PatternEngine.frame(WheelSample.ZERO, true, config, 0)
        assertEquals(BrightnessConfig.MIN_MAX_BRIGHTNESS, frame.brightness)
        assertTrue(frame.level > 0, "critical must be visibly lit")
    }

    @Test
    fun `critical reaches every board at once even when staggered`() {
        val config = EngineConfig(sync = SyncMode.Staggered(SyncMode.Staggered.MAX_DELAY_MS))
        // The alert starts "now"; history only contains calm riding.
        val history = TelemetryHistory.of(0, WheelSample(10.0, 10.0))
        val frames = PatternEngine.frames(history, true, config, PatternEngine.MAX_BOARDS, timeMs = 1)
        assertEquals(PatternEngine.MAX_BOARDS, frames.size)
        assertTrue(frames.all { it.critical && it.color == Rgb.RED && it.brightness == 1.0 })
    }

    @Test
    fun `flashing critical is always red and never dark`() {
        val config = EngineConfig(criticalStyle = CriticalStyle.FLASH)
        val seen = mutableSetOf<Double>()
        for (timeMs in 0L..2000L step 10) {
            val frame = PatternEngine.frame(WheelSample.ZERO, true, config, timeMs)
            assertEquals(Rgb.RED, frame.color)
            assertTrue(frame.level > 0, "dark at $timeMs ms")
            seen += frame.brightness
        }
        assertEquals(setOf(1.0, PatternEngine.CRITICAL_FLASH_LOW), seen)
    }

    @Test
    fun `flashing critical keeps all boards in phase`() {
        val config = EngineConfig(criticalStyle = CriticalStyle.FLASH, sync = SyncMode.Staggered(125))
        for (timeMs in 0L..1000L step 50) {
            val frames = PatternEngine.frames(TelemetryHistory.EMPTY, true, config, 4, timeMs)
            assertEquals(1, frames.toSet().size, "boards disagree at $timeMs ms")
        }
    }

    @Test
    fun `pattern returns as soon as critical clears`() {
        val config = EngineConfig(pattern = Pattern.ContinuousGradient)
        val sample = WheelSample(0.0, 50.0)
        assertEquals(Rgb.RED, PatternEngine.frame(sample, true, config, 0).color)
        val after = PatternEngine.frame(sample, false, config, 1)
        assertEquals(Rgb.GREEN, after.color)
        assertEquals(false, after.critical)
    }
}
