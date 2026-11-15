package app.apexled.pattern

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The example tables of docs/padroes-cor.md, checked against the engine.
 * If one of these fails, either the mapping changed (update the document) or it regressed.
 */
class DocumentedExamplesTest {

    private fun hexAt(pattern: Pattern, pwmPercent: Double) =
        PatternEngine.frame(WheelSample(0.0, pwmPercent), false, EngineConfig(pattern = pattern), 0).color.toHex()

    @Test
    fun `P1 table`() {
        val expected = mapOf(
            0 to "#0000FF", 10 to "#0066FF", 20 to "#00CCFF", 25 to "#00FFFF", 30 to "#00FFCC",
            40 to "#00FF66", 50 to "#00FF00", 60 to "#66FF00", 70 to "#CCFF00", 75 to "#FFFF00",
            80 to "#FFCC00", 90 to "#FF6600", 100 to "#FF0000",
        )
        for ((percent, hex) in expected) {
            assertEquals(hex, hexAt(Pattern.ContinuousGradient, percent.toDouble()), "P1 at $percent %")
        }
    }

    @Test
    fun `P2 table with the default colours`() {
        val expected = mapOf(0 to "#00FF00", 25 to "#80FF00", 50 to "#FFFF00", 75 to "#FF8000", 100 to "#FF0000")
        for ((percent, hex) in expected) {
            assertEquals(hex, hexAt(Pattern.ThreeColorGradient(), percent.toDouble()), "P2 at $percent %")
        }
    }

    @Test
    fun `P3 example - slow green breathing below 60 percent, fast amber blink above 80`() {
        val pulse = Pattern.Pulse(
            items = listOf(
                PulseItem(Rgb.GREEN, 0.5, 0.0, 60.0, PulseShape.BREATHE, minBrightness = 0.2, maxBrightness = 1.0),
                PulseItem(Rgb(255, 160, 0), 3.0, 80.0, 100.0, PulseShape.BLINK),
            ),
            idle = IdleLight.Steady(Rgb.GREEN, 1.0),
        )
        fun at(percent: Double, timeMs: Long) =
            PatternEngine.frame(WheelSample(0.0, percent), false, EngineConfig(pattern = pulse), timeMs)

        assertEquals(LedFrame(Rgb.GREEN, 0.2), at(30.0, 0).let { it.copy(brightness = Math.round(it.brightness * 100) / 100.0) })
        assertEquals(LedFrame(Rgb.GREEN, 1.0), at(30.0, 1_000).let { it.copy(brightness = Math.round(it.brightness * 100) / 100.0) })
        assertEquals(LedFrame(Rgb.GREEN, 1.0), at(70.0, 0))
        assertEquals(LedFrame(Rgb(255, 160, 0), 1.0), at(90.0, 0))
        assertEquals(LedFrame(Rgb(255, 160, 0), 0.0), at(90.0, 167))
        assertEquals(LedFrame(Rgb(255, 160, 0), 1.0), at(90.0, 334))
    }

    @Test
    fun `transition example - green red green blue over speed`() {
        val config = EngineConfig(
            pattern = Pattern.ColorSequence(),
            input = InputConfig(InputSource.SPEED, speedMaxKmh = 60.0),
        )
        fun hexAtSpeed(kmh: Double) = PatternEngine.frame(WheelSample(kmh, 0.0), false, config, 0).color.toHex()
        assertEquals("#00FF00", hexAtSpeed(0.0))
        assertEquals("#808000", hexAtSpeed(10.0))
        assertEquals("#FF0000", hexAtSpeed(20.0))
        assertEquals("#00FF00", hexAtSpeed(40.0))
        assertEquals("#008080", hexAtSpeed(50.0))
        assertEquals("#0000FF", hexAtSpeed(60.0))
    }

    @Test
    fun `brightness to level table`() {
        val expected = mapOf(0.0 to 0, 0.05 to 13, 0.25 to 64, 0.5 to 128, 0.75 to 191, 1.0 to 255)
        for ((brightness, level) in expected) assertEquals(level, LedFrame(Rgb.WHITE, brightness).level, "at $brightness")
    }
}
