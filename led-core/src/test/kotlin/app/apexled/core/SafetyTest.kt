package app.apexled.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyTest {

    private val calm = WheelTelemetry(speedKmh = 20.0, pwmPercent = 30.0, temperatureC = 40.0, currentA = 10.0, voltageV = 140.0)

    @Test
    fun `nothing is critical by default except an app alarm`() {
        val config = SafetyConfig()
        assertEquals(emptySet(), config.causes(calm))
        assertEquals(emptySet(), config.causes(calm.copy(speedKmh = 200.0, pwmPercent = 100.0, temperatureC = 150.0)))
        assertEquals(setOf(CriticalCause.APP_ALARM), config.causes(calm.copy(appAlarm = true)))
    }

    @Test
    fun `each threshold triggers at its limit and not just below`() {
        val config = SafetyConfig(pwmPercent = 80.0, speedKmh = 60.0, temperatureC = 70.0, currentA = 100.0, voltageBelowV = 120.0)
        assertEquals(emptySet(), config.causes(calm))

        assertEquals(emptySet(), config.causes(calm.copy(pwmPercent = 79.9)))
        assertEquals(setOf(CriticalCause.PWM), config.causes(calm.copy(pwmPercent = 80.0)))

        assertEquals(emptySet(), config.causes(calm.copy(speedKmh = 59.9)))
        assertEquals(setOf(CriticalCause.SPEED), config.causes(calm.copy(speedKmh = 60.0)))

        assertEquals(emptySet(), config.causes(calm.copy(temperatureC = 69.9)))
        assertEquals(setOf(CriticalCause.TEMPERATURE), config.causes(calm.copy(temperatureC = 70.0)))

        assertEquals(emptySet(), config.causes(calm.copy(currentA = 99.9)))
        assertEquals(setOf(CriticalCause.CURRENT), config.causes(calm.copy(currentA = 100.0)))

        assertEquals(emptySet(), config.causes(calm.copy(voltageV = 120.1)))
        assertEquals(setOf(CriticalCause.VOLTAGE), config.causes(calm.copy(voltageV = 120.0)))
    }

    @Test
    fun `direction does not matter for speed pwm and current`() {
        val config = SafetyConfig(pwmPercent = 80.0, speedKmh = 60.0, currentA = 100.0)
        val reverse = calm.copy(speedKmh = -61.0, pwmPercent = -81.0, currentA = -101.0)
        assertEquals(setOf(CriticalCause.PWM, CriticalCause.SPEED, CriticalCause.CURRENT), config.causes(reverse))
    }

    @Test
    fun `values the wheel does not report never trigger`() {
        val config = SafetyConfig(temperatureC = 70.0, currentA = 100.0, voltageBelowV = 120.0)
        val bare = WheelTelemetry(speedKmh = 10.0, pwmPercent = 10.0)
        assertEquals(emptySet(), config.causes(bare))
        assertEquals(emptySet(), config.causes(bare.copy(temperatureC = Double.NaN, currentA = Double.NaN, voltageV = Double.NaN)))
    }

    @Test
    fun `onset is immediate and release is held`() {
        val config = SafetyConfig(pwmPercent = 80.0, holdMs = 1_000)
        val monitor = CriticalMonitor()
        val hot = calm.copy(pwmPercent = 90.0)

        assertFalse(monitor.update(calm, config, 0).active)
        assertEquals(CriticalState(true, setOf(CriticalCause.PWM)), monitor.update(hot, config, 100))

        // Cause gone: still shown, without a cause, until the hold runs out.
        assertEquals(CriticalState(true, emptySet()), monitor.update(calm, config, 200))
        assertTrue(monitor.update(calm, config, 1_099).active)
        assertFalse(monitor.update(calm, config, 1_100).active)
        assertFalse(monitor.update(calm, config, 5_000).active)
    }

    @Test
    fun `the hold restarts every time the cause returns`() {
        val config = SafetyConfig(pwmPercent = 80.0, holdMs = 1_000)
        val monitor = CriticalMonitor()
        val hot = calm.copy(pwmPercent = 90.0)
        monitor.update(hot, config, 0)
        monitor.update(calm, config, 900)
        monitor.update(hot, config, 950)
        assertTrue(monitor.update(calm, config, 1_900).active)
        assertFalse(monitor.update(calm, config, 1_950).active)
    }

    @Test
    fun `losing telemetry releases after the hold`() {
        val config = SafetyConfig(holdMs = 500)
        val monitor = CriticalMonitor()
        assertTrue(monitor.update(calm.copy(appAlarm = true), config, 0).active)
        assertTrue(monitor.update(null, config, 400).active)
        assertFalse(monitor.update(null, config, 500).active)
    }

    @Test
    fun `zero hold releases at once`() {
        val config = SafetyConfig(pwmPercent = 80.0, holdMs = 0)
        val monitor = CriticalMonitor()
        assertTrue(monitor.update(calm.copy(pwmPercent = 95.0), config, 0).active)
        assertFalse(monitor.update(calm, config, 1).active)
    }

    @Test
    fun `thresholds are validated`() {
        assertFailsWith<IllegalArgumentException> { SafetyConfig(pwmPercent = 0.0) }
        assertFailsWith<IllegalArgumentException> { SafetyConfig(pwmPercent = 101.0) }
        assertFailsWith<IllegalArgumentException> { SafetyConfig(speedKmh = -5.0) }
        assertFailsWith<IllegalArgumentException> { SafetyConfig(holdMs = -1) }
        assertFailsWith<IllegalArgumentException> { SafetyConfig(holdMs = SafetyConfig.MAX_HOLD_MS + 1) }
    }
}
