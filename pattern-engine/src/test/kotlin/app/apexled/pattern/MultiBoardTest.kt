package app.apexled.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultiBoardTest {

    private fun pwm(percent: Double) = WheelSample(0.0, percent)

    private fun rampHistory(): TelemetryHistory {
        // PWM climbs 10 % every 100 ms: 0 % at t=0 ... 100 % at t=1000.
        var history = TelemetryHistory.EMPTY
        for (step in 0..10) history = history.plus(step * 100L, pwm(step * 10.0))
        return history
    }

    @Test
    fun `synchronised boards all show the same frame`() {
        val frames = PatternEngine.frames(rampHistory(), false, EngineConfig(), 10, timeMs = 500)
        assertEquals(10, frames.size)
        assertEquals(1, frames.toSet().size)
        assertEquals(Rgb.GREEN, frames.first().color)
    }

    @Test
    fun `staggered boards replay board 0 with a delay per board`() {
        val config = EngineConfig(sync = SyncMode.Staggered(delayMs = 100))
        val history = rampHistory()
        val frames = PatternEngine.frames(history, false, config, 4, timeMs = 1000)
        for (index in 0..3) {
            val whatBoard0Showed = PatternEngine.frames(history, false, config, 1, timeMs = 1000L - index * 100).single()
            assertEquals(whatBoard0Showed, frames[index], "board $index")
        }
        // Board 0 is at 100 % (red); board 3 is 300 ms behind, at 70 %.
        assertEquals(Rgb.RED, frames[0].color)
        assertEquals(Rgb.fromHue(240.0 * 0.3), frames[3].color)
    }

    @Test
    fun `staggered pulse is phase shifted per board`() {
        val blink = Pattern.Pulse(listOf(PulseItem(frequencyHz = 1.0)))
        val config = EngineConfig(pattern = blink, sync = SyncMode.Staggered(delayMs = 500))
        val frames = PatternEngine.frames(TelemetryHistory.of(0, pwm(50.0)), false, config, 2, timeMs = 10_000)
        assertEquals(1.0, frames[0].brightness)
        assertEquals(0.0, frames[1].brightness)
    }

    @Test
    fun `zero delay behaves like synchronised`() {
        val staggered = EngineConfig(sync = SyncMode.Staggered(delayMs = 0))
        val history = rampHistory()
        assertEquals(
            PatternEngine.frames(history, false, EngineConfig(), 10, 730),
            PatternEngine.frames(history, false, staggered, 10, 730),
        )
    }

    @Test
    fun `boards beyond the start of history hold the earliest known sample`() {
        val config = EngineConfig(sync = SyncMode.Staggered(delayMs = 2000))
        val history = TelemetryHistory.of(5_000, pwm(100.0))
        val frames = PatternEngine.frames(history, false, config, 10, timeMs = 5_000)
        assertTrue(frames.all { it.color == Rgb.RED })
    }

    @Test
    fun `board count is limited to ten`() {
        assertEquals(emptyList(), PatternEngine.frames(TelemetryHistory.EMPTY, false, EngineConfig(), 0, 0))
        assertEquals(10, PatternEngine.frames(TelemetryHistory.EMPTY, false, EngineConfig(), 10, 0).size)
        assertFailsWith<IllegalArgumentException> {
            PatternEngine.frames(TelemetryHistory.EMPTY, false, EngineConfig(), 11, 0)
        }
    }

    @Test
    fun `stagger delay is bounded`() {
        assertFailsWith<IllegalArgumentException> { SyncMode.Staggered(delayMs = -1) }
        assertFailsWith<IllegalArgumentException> { SyncMode.Staggered(delayMs = SyncMode.Staggered.MAX_DELAY_MS + 1) }
    }
}
