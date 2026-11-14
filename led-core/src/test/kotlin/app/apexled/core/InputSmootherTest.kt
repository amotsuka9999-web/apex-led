package app.apexled.core

import app.apexled.pattern.WheelSample
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputSmootherTest {

    @Test
    fun `the first sample passes through`() {
        val smoother = InputSmoother()
        assertEquals(WheelSample(30.0, 40.0), smoother.update(WheelSample(30.0, 40.0), 250, 0))
    }

    @Test
    fun `a step is approached exponentially`() {
        val smoother = InputSmoother()
        smoother.update(WheelSample(0.0, 0.0), 250, 0)
        val afterOneTimeConstant = smoother.update(WheelSample(0.0, 100.0), 250, 250)
        assertEquals(100.0 * (1 - exp(-1.0)), afterOneTimeConstant.pwmPercent, 1e-9)

        var value = afterOneTimeConstant
        for (step in 1..40) value = smoother.update(WheelSample(0.0, 100.0), 250, 250 + step * 50L)
        assertTrue(value.pwmPercent > 99.9, "converges, got ${value.pwmPercent}")
        assertTrue(value.pwmPercent <= 100.0, "never overshoots")
    }

    @Test
    fun `zero time constant disables smoothing`() {
        val smoother = InputSmoother()
        smoother.update(WheelSample(0.0, 0.0), 0, 0)
        assertEquals(WheelSample(50.0, 100.0), smoother.update(WheelSample(50.0, 100.0), 0, 10))
    }

    @Test
    fun `invalid readings are ignored`() {
        val smoother = InputSmoother()
        smoother.update(WheelSample(10.0, 20.0), 250, 0)
        assertEquals(WheelSample(10.0, 20.0), smoother.update(WheelSample(Double.NaN, 20.0), 250, 100))
        assertEquals(WheelSample.ZERO, InputSmoother().update(WheelSample(Double.NaN, Double.NaN), 250, 0))
    }

    @Test
    fun `reset forgets the past`() {
        val smoother = InputSmoother()
        smoother.update(WheelSample(0.0, 0.0), 250, 0)
        smoother.reset()
        assertEquals(WheelSample(0.0, 80.0), smoother.update(WheelSample(0.0, 80.0), 250, 10))
    }
}
