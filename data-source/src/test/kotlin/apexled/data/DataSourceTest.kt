package apexled.data

import apexled.core.Clock
import apexled.core.WheelProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DataSourceTest {
    private val manual = SimManual()

    @Test fun accelerationReachesMaxAndReturns() {
        val s = { t: Float -> SimulatorModel.sample(SimScenario.ACCELERATION, t, manual, 50f) }
        assertEquals(0f, s(0f).speedKmh, 0.01f)
        assertEquals(50f, s(16f).speedKmh, 0.01f)
        assertEquals(0f, s(24f - 0.001f).speedKmh, 0.1f)
        assertTrue(s(13.9f).pwm > 0.95f)
    }

    @Test fun brakingProducesRegenCurrentAndSpeedDrops() {
        val a = SimulatorModel.sample(SimScenario.BRAKING, 3f, manual, 50f)
        val b = SimulatorModel.sample(SimScenario.BRAKING, 7.5f, manual, 50f)
        assertTrue(b.currentA < 0 && a.currentA > 0)
        assertTrue(b.speedKmh < a.speedKmh)
    }

    @Test fun climbRaisesPwmWhileSpeedDrops() {
        val a = SimulatorModel.sample(SimScenario.CLIMB, 1f, manual, 50f)
        val b = SimulatorModel.sample(SimScenario.CLIMB, 19f, manual, 50f)
        assertTrue(b.pwm > a.pwm && b.pwm > 0.9f)
        assertTrue(b.speedKmh < a.speedKmh)
    }

    @Test fun signalLossHasSilentWindow() {
        assertTrue(SimulatorModel.sample(SimScenario.SIGNAL_LOSS, 2f, manual, 50f).signal)
        assertFalse(SimulatorModel.sample(SimScenario.SIGNAL_LOSS, 8f, manual, 50f).signal)
    }

    @Test fun simulatorEmitsAtTenHertzAndStopsEmittingDuringSignalLoss() = runTest {
        val scope: TestScope = this
        var now = 0L
        val clock = Clock { scope.currentTime }
        val src = SimulatorSource(backgroundScope, { WheelProfile.begodeA2() }, clock)
        assertNull(src.telemetry.value)
        src.setScenario(SimScenario.SIGNAL_LOSS)
        src.start()
        advanceTimeBy(1000)
        val t1 = src.telemetry.value
        assertNotNull(t1)
        assertTrue(clock.nowMs() - t1.timestampMs <= 100)
        advanceTimeBy(8000)  // dentro da janela de 6–12 s sem dados
        val last = src.telemetry.value!!.timestampMs
        assertTrue(clock.nowMs() - last >= 1500, "deveria ter envelhecido; idade=${clock.nowMs() - last}")
        src.stop()
        assertNull(src.telemetry.value)
        now += 0
    }

    @Test fun wheelLogBroadcastUnitsAreConverted() {
        val t = apexled.data.WheelLogBridgeSource.parseIntent(
            speed = 3050, pwm = 0.72, voltage = 12580, current = -1234, battery = 83, temp = 4139, nowMs = 5,
        )
        assertEquals(30.5f, t.speedKmh, 0.001f)
        assertEquals(0.72f, t.pwm!!, 0.0001f)
        assertEquals(125.80f, t.voltage, 0.001f)
        assertEquals(-12.34f, t.currentA!!, 0.001f)
        assertEquals(83f, t.batteryPercent!!, 0.001f)
        assertEquals(41.39f, t.temperatureC!!, 0.001f)
    }

    @Test fun wheelLogMissingFieldsBecomeNull() {
        val t = apexled.data.WheelLogBridgeSource.parseIntent(0, Double.NaN, 6000, 0, -1, Int.MIN_VALUE, 0)
        assertNull(t.pwm); assertNull(t.batteryPercent); assertNull(t.temperatureC)
    }
}
