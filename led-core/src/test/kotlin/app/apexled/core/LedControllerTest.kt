package app.apexled.core

import app.apexled.device.BoardCommand
import app.apexled.device.BoardInfo
import app.apexled.device.BoardManager
import app.apexled.device.BoardState
import app.apexled.device.LightMode
import app.apexled.device.PowerOnState
import app.apexled.device.protocol.Sp63xProtocol
import app.apexled.device.sim.SimulatedBoard
import app.apexled.pattern.BrightnessConfig
import app.apexled.pattern.CriticalStyle
import app.apexled.pattern.InputConfig
import app.apexled.pattern.InputSource
import app.apexled.pattern.Pattern
import app.apexled.pattern.PulseItem
import app.apexled.pattern.Rgb
import app.apexled.pattern.SyncMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LedControllerTest {

    /** A controller wired to simulated boards on virtual time. Smoothing is off unless a test turns it on. */
    private class Rig(
        private val scope: TestScope,
        val store: SettingsStore = InMemorySettingsStore(LedSettings(smoothingMs = 0)),
    ) {
        val sims = mutableMapOf<String, SimulatedBoard>()
        private val now = { scope.testScheduler.currentTime }
        val manager = BoardManager(
            scope = scope.backgroundScope,
            transportFactory = { info -> SimulatedBoard(clock = now).also { sims[info.id] = it } },
            clock = now,
        )
        val controller = LedController(scope.backgroundScope, manager, store, now)

        fun info(index: Int) = BoardInfo("board-$index", "Board $index", "AA:BB:CC:00:00:%02X".format(index), "sp63x")

        fun start(boardCount: Int) {
            controller.start()
            for (index in 1..boardCount) controller.addBoard(info(index))
        }

        fun light(index: Int) = sims.getValue("board-$index").firmware.light

        fun colors() = sims.toSortedMap(compareBy { it.removePrefix("board-").toInt() }).values.map { it.firmware.light.color }

        /** Feeds the same reading every 100 ms for [durationMs], as a connected wheel would. */
        fun ride(telemetry: WheelTelemetry, durationMs: Long) {
            var elapsed = 0L
            while (elapsed < durationMs) {
                controller.onTelemetry(telemetry)
                scope.advanceTimeBy(100)
                elapsed += 100
            }
        }
    }

    private fun pwm(percent: Double, appAlarm: Boolean = false) =
        WheelTelemetry(speedKmh = 0.0, pwmPercent = percent, appAlarm = appAlarm)

    // ---- pattern mode --------------------------------------------------------------------

    @Test
    fun `telemetry drives the colour on every board`() = runTest {
        val rig = Rig(this)
        rig.start(3)

        rig.ride(pwm(0.0), 500)
        assertEquals(List(3) { Rgb.BLUE }, rig.colors())

        rig.ride(pwm(50.0), 500)
        assertEquals(List(3) { Rgb.GREEN }, rig.colors())

        rig.ride(pwm(100.0), 500)
        assertEquals(List(3) { Rgb.RED }, rig.colors())
        assertTrue((1..3).all { rig.light(it).showsSolidColor })
        assertFalse(rig.controller.critical.value.active, "100 % PWM is the hot end of the pattern, not an alert")
    }

    @Test
    fun `the user can switch the driving input to speed`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update {
            it.copy(engine = it.engine.copy(input = InputConfig(InputSource.SPEED, speedMaxKmh = 40.0)))
        }
        rig.ride(WheelTelemetry(speedKmh = 20.0, pwmPercent = 100.0), 500)
        assertEquals(Rgb.GREEN, rig.light(1).color)
    }

    @Test
    fun `changing the pattern while riding takes effect at once`() = runTest {
        val rig = Rig(this)
        rig.start(2)
        rig.ride(pwm(50.0), 500)
        assertEquals(List(2) { Rgb.GREEN }, rig.colors())

        rig.controller.update {
            it.copy(engine = it.engine.copy(pattern = Pattern.ThreeColorGradient(Rgb.WHITE, Rgb(200, 0, 200), Rgb.YELLOW)))
        }
        rig.ride(pwm(50.0), 300)
        assertEquals(List(2) { Rgb(200, 0, 200) }, rig.colors())
    }

    @Test
    fun `staggered boards trail the first one`() = runTest {
        val rig = Rig(this)
        rig.start(3)
        rig.controller.update { it.copy(engine = it.engine.copy(sync = SyncMode.Staggered(1_000))) }
        rig.ride(pwm(0.0), 3_000)
        assertEquals(List(3) { Rgb.BLUE }, rig.colors())

        rig.ride(pwm(100.0), 500)
        assertEquals(listOf(Rgb.RED, Rgb.BLUE, Rgb.BLUE), rig.colors())
        rig.ride(pwm(100.0), 1_000)
        assertEquals(listOf(Rgb.RED, Rgb.RED, Rgb.BLUE), rig.colors())
        rig.ride(pwm(100.0), 1_000)
        assertEquals(listOf(Rgb.RED, Rgb.RED, Rgb.RED), rig.colors())
    }

    @Test
    fun `a pulse keeps running between telemetry updates`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update {
            it.copy(engine = it.engine.copy(pattern = Pattern.Pulse(listOf(PulseItem(Rgb.GREEN, frequencyHz = 1.0)))))
        }
        rig.controller.onTelemetry(pwm(50.0))

        val levels = mutableSetOf<Int>()
        repeat(40) {
            advanceTimeBy(50)
            levels += rig.light(1).level
        }
        assertEquals(setOf(0, 255), levels)
    }

    @Test
    fun `smoothing softens a jump in the input`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update { it.copy(smoothingMs = 1_000) }
        rig.ride(pwm(0.0), 500)
        rig.ride(pwm(100.0), 300)
        val soon = rig.light(1).color
        assertTrue(soon != Rgb.RED && soon != Rgb.BLUE, "still on the way: $soon")
        rig.ride(pwm(100.0), 10_000)
        assertEquals(Rgb.RED, rig.light(1).color)
    }

    // ---- critical alert ------------------------------------------------------------------

    @Test
    fun `an app alarm turns every board red and the pattern returns after the hold`() = runTest {
        val rig = Rig(this)
        rig.start(3)
        rig.ride(pwm(50.0), 500)

        rig.ride(pwm(50.0, appAlarm = true), 300)
        assertEquals(List(3) { Rgb.RED }, rig.colors())
        assertEquals(setOf(CriticalCause.APP_ALARM), rig.controller.critical.value.causes)
        assertTrue(rig.controller.frames.value.all { it.critical })

        rig.ride(pwm(50.0), 1_000)
        assertEquals(List(3) { Rgb.RED }, rig.colors(), "held for holdMs after the alarm clears")

        rig.ride(pwm(50.0), 1_000)
        assertEquals(List(3) { Rgb.GREEN }, rig.colors())
        assertFalse(rig.controller.critical.value.active)
    }

    @Test
    fun `a configured threshold triggers the alert`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update { it.copy(safety = SafetyConfig(pwmPercent = 80.0)) }

        rig.ride(pwm(75.0), 300)
        assertFalse(rig.controller.critical.value.active)
        rig.ride(pwm(80.0), 300)
        assertEquals(setOf(CriticalCause.PWM), rig.controller.critical.value.causes)
        assertEquals(Rgb.RED, rig.light(1).color)
    }

    @Test
    fun `the alert is not delayed by staggering`() = runTest {
        val rig = Rig(this)
        rig.start(10)
        rig.controller.update { it.copy(engine = it.engine.copy(sync = SyncMode.Staggered(SyncMode.Staggered.MAX_DELAY_MS))) }
        rig.ride(pwm(0.0), 1_000)

        rig.controller.onTelemetry(pwm(0.0, appAlarm = true))
        advanceTimeBy(150)

        assertEquals(List(10) { Rgb.RED }, rig.colors())
    }

    @Test
    fun `the alert is not delayed by smoothing`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update { it.copy(smoothingMs = LedSettings.MAX_SMOOTHING_MS, safety = SafetyConfig(pwmPercent = 90.0)) }
        rig.ride(pwm(0.0), 1_000)

        rig.controller.onTelemetry(pwm(95.0))
        assertTrue(rig.controller.critical.value.active, "detected on the raw reading, in the same call")
        advanceTimeBy(150)
        assertEquals(Rgb.RED, rig.light(1).color)
    }

    @Test
    fun `no pattern setting can suppress the alert`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update {
            it.copy(
                engine = it.engine.copy(
                    pattern = Pattern.Pulse(listOf(PulseItem(Rgb.BLUE, maxBrightness = 0.0))),
                    brightness = BrightnessConfig(master = 0.0, max = BrightnessConfig.MIN_MAX_BRIGHTNESS),
                ),
                telemetryLoss = TelemetryLossBehavior.OFF,
            )
        }
        rig.ride(pwm(10.0, appAlarm = true), 500)

        assertEquals(Rgb.RED, rig.light(1).color)
        assertTrue(rig.light(1).power)
        assertTrue(rig.light(1).level > 0)
    }

    @Test
    fun `the alert overrides manual mode and the manual light is restored afterwards`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        val manual = ManualLight(mode = LightMode.DYNAMIC, effect = 12, level = 180, effectSpeed = 3)
        rig.controller.update { it.copy(mode = ControlMode.MANUAL, manual = manual) }
        rig.ride(pwm(20.0), 500)
        assertEquals(LightMode.DYNAMIC, rig.light(1).mode)
        assertEquals(12, rig.light(1).effect)

        rig.ride(pwm(20.0, appAlarm = true), 500)
        assertTrue(rig.light(1).showsSolidColor)
        assertEquals(Rgb.RED, rig.light(1).color)

        rig.ride(pwm(20.0), 3_000)
        assertEquals(LightMode.DYNAMIC, rig.light(1).mode)
        assertEquals(12, rig.light(1).effect)
        assertEquals(180, rig.light(1).level)
        assertEquals(3, rig.light(1).effectSpeed)
        assertEquals(emptyList(), rig.controller.frames.value)
    }

    @Test
    fun `a board that was away shows the alert as soon as it is back`() = runTest {
        val rig = Rig(this)
        rig.start(2)
        rig.ride(pwm(50.0), 500)

        rig.sims.getValue("board-2").powerOff()
        rig.ride(pwm(50.0, appAlarm = true), 1_000)
        assertEquals(listOf(Rgb.RED, Rgb.GREEN), rig.colors())

        rig.sims.getValue("board-2").powerOn()
        rig.ride(pwm(50.0, appAlarm = true), 3_000)
        assertEquals(listOf(Rgb.RED, Rgb.RED), rig.colors())
    }

    @Test
    fun `flashing style reaches the boards as red at two levels`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update { it.copy(engine = it.engine.copy(criticalStyle = CriticalStyle.FLASH)) }

        val levels = mutableSetOf<Int>()
        repeat(30) {
            rig.controller.onTelemetry(pwm(0.0, appAlarm = true))
            advanceTimeBy(100)
            assertEquals(Rgb.RED, rig.light(1).color)
            levels += rig.light(1).level
        }
        assertEquals(setOf(64, 255), levels)
    }

    // ---- brightness ----------------------------------------------------------------------

    @Test
    fun `nothing sent to a board exceeds the brightness ceiling`() = runTest {
        val rig = Rig(this)
        rig.start(2)
        rig.controller.update {
            it.copy(
                engine = it.engine.copy(
                    brightness = BrightnessConfig(master = 1.0, max = 0.4),
                    sync = SyncMode.Staggered(200),
                ),
                safety = SafetyConfig(pwmPercent = 90.0),
            )
        }
        for (percent in 0..100 step 5) rig.ride(pwm(percent.toDouble()), 200)

        val ceiling = Math.round(0.4 * 255).toInt()
        val levels = rig.sims.values.flatMap { sim ->
            sim.writes
                .filter { (it.payload[1].toInt() and 0xFF) == Sp63xProtocol.Opcode.STATIC_COLOR }
                .map { it.payload[9].toInt() and 0xFF }
        }
        assertTrue(levels.size > 20, "frames were sent")
        assertEquals(ceiling, levels.max())
        assertTrue(rig.controller.critical.value.active, "the run ended in the critical range")
        assertEquals(ceiling, rig.light(1).level)
    }

    // ---- telemetry loss ------------------------------------------------------------------

    @Test
    fun `without telemetry the pattern falls to its cold end by default`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.ride(pwm(100.0), 500)
        assertEquals(Rgb.RED, rig.light(1).color)

        advanceTimeBy(rig.controller.settings.value.telemetryTimeoutMs + 500)
        assertEquals(Rgb.BLUE, rig.light(1).color)
        assertTrue(rig.light(1).level > 0)
    }

    @Test
    fun `hold-last and off are available for telemetry loss`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update { it.copy(telemetryLoss = TelemetryLossBehavior.HOLD_LAST) }
        rig.ride(pwm(100.0), 500)
        advanceTimeBy(10_000)
        assertEquals(Rgb.RED, rig.light(1).color)

        rig.controller.update { it.copy(telemetryLoss = TelemetryLossBehavior.OFF) }
        advanceTimeBy(500)
        assertEquals(0, rig.light(1).level)
    }

    @Test
    fun `a stale alarm does not keep the boards red forever`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.ride(pwm(30.0, appAlarm = true), 500)
        assertEquals(Rgb.RED, rig.light(1).color)

        // The wheel disconnects mid-alarm: no more readings.
        advanceTimeBy(10_000)
        assertFalse(rig.controller.critical.value.active)
        assertEquals(Rgb.BLUE, rig.light(1).color)
    }

    // ---- manual mode ---------------------------------------------------------------------

    @Test
    fun `manual mode applies the chosen light and follows changes`() = runTest {
        val rig = Rig(this)
        rig.start(2)
        rig.ride(pwm(50.0), 500)

        rig.controller.update { it.copy(mode = ControlMode.MANUAL, manual = ManualLight(color = Rgb(1, 2, 3), level = 99)) }
        advanceTimeBy(1_000)
        assertEquals(List(2) { Rgb(1, 2, 3) }, rig.colors())
        assertEquals(99, rig.light(1).level)

        rig.controller.update { it.copy(manual = it.manual.copy(power = false)) }
        advanceTimeBy(1_000)
        assertFalse(rig.light(1).power)
        assertFalse(rig.light(2).power)

        // Telemetry keeps arriving but no longer drives the boards.
        rig.ride(pwm(100.0), 1_000)
        assertFalse(rig.light(1).power)
    }

    @Test
    fun `returning to pattern mode resumes the pattern`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        rig.controller.update { it.copy(mode = ControlMode.MANUAL, manual = ManualLight(power = false)) }
        rig.ride(pwm(50.0), 1_000)
        assertFalse(rig.light(1).power)

        rig.controller.update { it.copy(mode = ControlMode.PATTERN) }
        rig.ride(pwm(50.0), 500)
        assertTrue(rig.light(1).showsSolidColor)
        assertEquals(Rgb.GREEN, rig.light(1).color)
    }

    @Test
    fun `board options are forwarded to the boards`() = runTest {
        val rig = Rig(this)
        rig.start(2)
        runCurrent()
        assertEquals(emptyList(), rig.controller.sendToBoards(BoardCommand.SetPowerOnState(PowerOnState.LAST_STATE)))
        advanceTimeBy(500)
        for (sim in rig.sims.values) {
            assertTrue(sim.writes.any { (it.payload[1].toInt() and 0xFF) == Sp63xProtocol.Opcode.POWER_ON_STATE })
        }
    }

    // ---- boards --------------------------------------------------------------------------

    @Test
    fun `up to ten boards and each keeps its own name and address`() = runTest {
        val rig = Rig(this)
        rig.start(10)
        assertEquals(BoardManager.AddResult.LimitReached, rig.controller.addBoard(rig.info(11)))

        assertTrue(rig.controller.renameBoard("board-3", "Left pedal"))
        runCurrent() // saving happens in the background
        val saved = rig.store.load().boards
        assertEquals(10, saved.size)
        assertEquals("Left pedal", saved[2].name)
        assertEquals("AA:BB:CC:00:00:03", saved[2].address)
        assertEquals(10, saved.map { it.address }.toSet().size)

        rig.ride(pwm(50.0), 1_000)
        assertEquals(List(10) { Rgb.GREEN }, rig.colors())
    }

    @Test
    fun `a board dropping out and returning does not disturb the others`() = runTest {
        val rig = Rig(this)
        rig.start(3)
        rig.ride(pwm(0.0), 500)

        rig.sims.getValue("board-2").powerOff()
        rig.ride(pwm(50.0), 1_000)
        assertEquals(listOf(Rgb.GREEN, Rgb.BLUE, Rgb.GREEN), rig.colors())
        assertIs<BoardState.Reconnecting>(rig.manager.boards.value[1].connection.state.value)

        rig.sims.getValue("board-2").powerOn()
        rig.ride(pwm(50.0), 5_000)
        assertEquals(List(3) { Rgb.GREEN }, rig.colors())
        assertEquals(BoardState.Ready, rig.manager.boards.value[1].connection.state.value)
    }

    @Test
    fun `removing and reordering boards is saved`() = runTest {
        val rig = Rig(this)
        rig.start(3)
        assertTrue(rig.controller.reorderBoards(listOf("board-3", "board-1", "board-2")))
        assertTrue(rig.controller.removeBoard("board-1"))
        assertFalse(rig.controller.removeBoard("board-1"))
        runCurrent() // saving happens in the background
        assertEquals(listOf("board-3", "board-2"), rig.store.load().boards.map { it.id })
        assertEquals(listOf("board-3", "board-2"), rig.controller.settings.value.boards.map { it.id })
    }

    // ---- persistence ---------------------------------------------------------------------

    @Test
    fun `settings and boards survive a restart`() = runTest {
        val store = InMemorySettingsStore(LedSettings(smoothingMs = 0))
        val first = Rig(this, store)
        first.start(2)
        first.controller.update {
            it.copy(
                engine = it.engine.copy(pattern = Pattern.ThreeColorGradient(), sync = SyncMode.Staggered(250)),
                safety = SafetyConfig(pwmPercent = 85.0),
            )
        }
        runCurrent()
        val saved = first.controller.settings.value
        first.controller.stop()

        val second = Rig(this, store)
        second.controller.start()
        assertEquals(saved, second.controller.settings.value)
        assertEquals(2, second.manager.size)

        second.ride(pwm(50.0), 2_000)
        assertEquals(List(2) { Rgb.YELLOW }, second.colors())
    }

    @Test
    fun `invalid settings are refused and nothing changes`() = runTest {
        val rig = Rig(this)
        rig.start(1)
        val before = rig.controller.settings.value
        assertFailsWith<IllegalArgumentException> { rig.controller.update { it.copy(smoothingMs = -1) } }
        assertFailsWith<IllegalArgumentException> {
            rig.controller.update { it.copy(boards = emptyList()) }
        }
        assertEquals(before, rig.controller.settings.value)
    }

    @Test
    fun `a failed save is reported and the next good save clears it`() = runTest {
        val flaky = object : SettingsStore {
            var fail = true
            var saved: LedSettings? = null
            override fun load() = LedSettings()
            override fun save(settings: LedSettings) {
                if (fail) throw IOException("disk full")
                saved = settings
            }
        }
        val rig = Rig(this, flaky)
        rig.start(0)
        assertNull(rig.controller.saveError.value)

        rig.controller.update { it.copy(smoothingMs = 100) }
        runCurrent()
        assertEquals("disk full", rig.controller.saveError.value)
        assertEquals(100, rig.controller.settings.value.smoothingMs, "the change still applies for this session")

        flaky.fail = false
        rig.controller.update { it.copy(smoothingMs = 200) }
        runCurrent()
        assertNull(rig.controller.saveError.value)
        assertEquals(200, assertNotNull(flaky.saved).smoothingMs)
    }

    @Test
    fun `stop disconnects the boards and keeps the settings`() = runTest {
        val rig = Rig(this)
        rig.start(2)
        rig.ride(pwm(50.0), 500)
        runCurrent()
        rig.controller.stop()

        assertTrue(rig.sims.values.none { it.connected })
        assertEquals(2, rig.store.load().boards.size)
    }
}
