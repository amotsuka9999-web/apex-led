package app.apexled.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TelemetryHistoryTest {

    private fun pwm(percent: Double) = WheelSample(0.0, percent)

    @Test
    fun `empty history reads as zero`() {
        assertEquals(WheelSample.ZERO, TelemetryHistory.EMPTY.sampleAt(123))
    }

    @Test
    fun `lookup returns the newest sample at or before the time`() {
        val history = TelemetryHistory.EMPTY.plus(100, pwm(1.0)).plus(200, pwm(2.0)).plus(300, pwm(3.0))
        assertEquals(pwm(1.0), history.sampleAt(50))
        assertEquals(pwm(1.0), history.sampleAt(100))
        assertEquals(pwm(1.0), history.sampleAt(199))
        assertEquals(pwm(2.0), history.sampleAt(200))
        assertEquals(pwm(3.0), history.sampleAt(300))
        assertEquals(pwm(3.0), history.sampleAt(9_999))
    }

    @Test
    fun `old samples are dropped but one stays at the retention edge`() {
        var history = TelemetryHistory.EMPTY
        for (step in 0..100) history = history.plus(step * 100L, pwm(step.toDouble()), retentionMs = 1_000)
        // Newest is t=10000, the window starts at t=9000.
        assertEquals(11, history.size)
        assertEquals(pwm(90.0), history.sampleAt(9_000))
        assertEquals(pwm(90.0), history.sampleAt(8_000))
    }

    @Test
    fun `a sample at the same time replaces the previous one`() {
        val history = TelemetryHistory.EMPTY.plus(100, pwm(1.0)).plus(100, pwm(9.0))
        assertEquals(1, history.size)
        assertEquals(pwm(9.0), history.sampleAt(100))
    }

    @Test
    fun `a sample from the past is ignored`() {
        val history = TelemetryHistory.EMPTY.plus(100, pwm(1.0))
        assertSame(history, history.plus(50, pwm(9.0)))
    }

    @Test
    fun `adding does not change the original`() {
        val original = TelemetryHistory.of(100, pwm(1.0))
        original.plus(200, pwm(2.0))
        assertEquals(1, original.size)
        assertEquals(pwm(1.0), original.sampleAt(500))
    }
}
