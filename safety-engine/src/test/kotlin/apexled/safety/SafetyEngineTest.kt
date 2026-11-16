package apexled.safety

import apexled.core.ColorScale
import apexled.core.ColorStop
import apexled.core.PwmCalibration
import apexled.core.Rgb
import apexled.core.Telemetry
import apexled.core.WheelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SafetyEngineTest {
    private val profile = WheelProfile.begodeA2().copy(maxSpeedKmh = 50f)
    private val pwmOnly = SafetyConfig(mode = SafetyMode.PWM, releaseSeconds = 0f)

    private fun tel(
        now: Long,
        pwm: Float? = 0.3f,
        speed: Float = 20f,
        battery: Float? = 80f,
        current: Float? = 5f,
        voltage: Float = 62f,
    ) = Telemetry(now, speed, pwm, voltage, current, battery, 30f)

    private fun SafetyEngine.step(t: Telemetry?, now: Long, cfg: SafetyConfig = pwmOnly, p: WheelProfile = profile) =
        update(t, now, p, cfg)

    // ---- escala própria de velocidade (km/h) ---------------------------------------------

    private val speedCfg = SafetyConfig(
        mode = SafetyMode.SPEED, releaseSeconds = 0f, speedLimitKmh = 50f,
        speedStops = listOf(ColorStop(0f, Rgb(0, 255, 0)), ColorStop(30f, Rgb(255, 255, 0)), ColorStop(45f, Rgb(255, 0, 0))),
        speedFlashKmh = 45f,
    )

    @Test fun ownSpeedScaleUsesKmh() {
        assertEquals(Rgb(255, 255, 0), SafetyEngine().step(tel(0, speed = 30f), 0, speedCfg).frame.color)
        assertEquals(Rgb(0, 255, 0), SafetyEngine().step(tel(0, speed = 0f), 0, speedCfg).frame.color)
        assertFalse(SafetyEngine().step(tel(0, speed = 40f), 0, speedCfg).flashing)
    }

    @Test fun ownSpeedScaleFlashesAtChosenSpeed() {
        assertTrue(SafetyEngine().step(tel(0, speed = 46f), 0, speedCfg).flashing)
    }

    @Test fun ownSpeedScaleIgnoredInPwmMode() {
        val out = SafetyEngine().step(tel(0, pwm = 0.30f, speed = 40f), 0, speedCfg.copy(mode = SafetyMode.PWM))
        assertEquals(Rgb(0, 220, 60), out.frame.color)
    }

    // ---- faixas de cor ----------------------------------------------------------------------

    @Test fun greenBelowSixtyPercent() {
        val out = SafetyEngine().step(tel(0, pwm = 0.30f), 0)
        assertEquals(Rgb(0, 220, 60), out.frame.color)
        assertEquals(SafetyLevel.OK, out.level)
        assertFalse(out.flashing)
        assertEquals(0.70f, out.margin, 1e-4f)
    }

    @Test fun yellowAtSeventyPercent() {
        assertEquals(Rgb(255, 210, 0), SafetyEngine().step(tel(0, pwm = 0.70f), 0).frame.color)
    }

    @Test fun orangeAtEightyPercent() {
        val out = SafetyEngine().step(tel(0, pwm = 0.80f), 0)
        assertEquals(Rgb(255, 110, 0), out.frame.color)
        assertEquals(SafetyLevel.ALERT, out.level)
    }

    @Test fun redAtNinetyPercentBlinksAboveIt() {
        val e = SafetyEngine()
        val on = e.step(tel(0, pwm = 0.95f), 0)           // fase "ligada" do pisca
        val off = e.step(tel(250, pwm = 0.95f), 250)      // 250 % 400 >= 200 → fase apagada
        assertTrue(on.flashing && off.flashing)
        assertEquals(Rgb(255, 0, 0), on.frame.color)
        assertEquals(255, on.frame.brightness)
        assertEquals(0, off.frame.brightness)
        assertEquals(SafetyLevel.CRITICAL, on.level)
    }

    @Test fun gradientIsContinuousBetweenStops() {
        val mid = SafetyEngine().step(tel(0, pwm = 0.65f), 0).frame.color
        val expected = ColorScale.colorAt(profile.pwmStops, 0.65f, stepped = false)
        assertEquals(expected, mid)
        assertNotEquals(Rgb(160, 220, 0), mid)
        assertNotEquals(Rgb(255, 210, 0), mid)
    }

    @Test fun usesEstimatedPwmWhenWheelDoesNotReportIt() {
        // 40,5 km/h a 84 V com calibração 50 km/h@84 V, fator 0,9 → 45 km/h = 100% → 0,9 de uso
        val p = profile.copy(pwmCalibration = PwmCalibration(50f, 84f, 0.9f))
        val out = SafetyEngine().step(tel(0, pwm = null, speed = 40.5f, voltage = 84f), 0, p = p)
        assertEquals(0.9f, out.usage, 1e-3f)
    }

    // ---- histerese --------------------------------------------------------------------------

    @Test fun flashHasHysteresisOnTheWayDown() {
        val e = SafetyEngine()
        assertTrue(e.step(tel(0, pwm = 0.92f), 0).flashing)
        // 0,89 ainda está dentro da folga de 0,03 → continua piscando
        assertTrue(e.step(tel(100, pwm = 0.89f), 100).flashing)
        // 0,86 já saiu da folga
        assertFalse(e.step(tel(200, pwm = 0.86f), 200).flashing)
    }

    @Test fun flashDoesNotStartBelowThreshold() {
        assertFalse(SafetyEngine().step(tel(0, pwm = 0.89f), 0).flashing)
    }

    @Test fun steppedColorsHoldBandUntilHysteresisIsExceeded() {
        val cfg = pwmOnly.copy(stepped = true)
        val e = SafetyEngine()
        val orange = Rgb(255, 110, 0)
        assertEquals(orange, e.step(tel(0, pwm = 0.81f), 0, cfg).frame.color)
        // 0,79 está a 0,01 abaixo do degrau de 0,80 → mantém laranja
        assertEquals(orange, e.step(tel(100, pwm = 0.79f), 100, cfg).frame.color)
        // 0,75 está a 0,05 abaixo → desce para o degrau amarelo
        assertEquals(Rgb(255, 210, 0), e.step(tel(200, pwm = 0.75f), 200, cfg).frame.color)
    }

    @Test fun steppedColorDoesNotFlickerOnNoiseAroundBoundary() {
        val cfg = pwmOnly.copy(stepped = true)
        val e = SafetyEngine()
        val seen = mutableSetOf<Rgb>()
        var now = 0L
        repeat(40) { i ->
            val pwm = 0.80f + if (i % 2 == 0) 0.01f else -0.01f
            seen += e.step(tel(now, pwm = pwm), now, cfg).frame.color
            now += 100
        }
        assertEquals(1, seen.size)
    }

    // ---- suavização -------------------------------------------------------------------------

    @Test fun alertRisesImmediately() {
        val cfg = pwmOnly.copy(releaseSeconds = 2f)
        val e = SafetyEngine()
        e.step(tel(0, pwm = 0.2f), 0, cfg)
        val out = e.step(tel(100, pwm = 0.95f), 100, cfg)
        assertEquals(0.95f, out.usage, 1e-4f)
        assertTrue(out.flashing)
    }

    @Test fun alertReleasesSlowly() {
        val cfg = pwmOnly.copy(releaseSeconds = 2f)
        val e = SafetyEngine()
        e.step(tel(0, pwm = 0.95f), 0, cfg)
        val out = e.step(tel(100, pwm = 0.2f), 100, cfg)
        // τ = 2 s, Δt = 0,1 s → cai apenas ~5% do degrau
        assertTrue(out.usage > 0.85f, "caiu rápido demais: ${out.usage}")
        assertTrue(out.usage < 0.95f)
        var now = 100L
        var last = out
        repeat(100) { now += 100; last = e.step(tel(now, pwm = 0.2f), now, cfg) }
        assertTrue(last.usage < 0.25f, "não relaxou: ${last.usage}")
    }

    @Test fun spikeStaysVisibleAfterItPasses() {
        val cfg = pwmOnly.copy(releaseSeconds = 1.5f)
        val e = SafetyEngine()
        var now = 0L
        repeat(10) { e.step(tel(now, pwm = 0.3f), now, cfg); now += 100 }
        e.step(tel(now, pwm = 0.85f), now, cfg); now += 100        // pico: sobe na hora (segurança)
        val after = e.step(tel(now, pwm = 0.3f), now, cfg)          // mas não some instantaneamente
        assertTrue(after.usage > 0.6f)
    }

    @Test fun attackFilterCanBeEnabled() {
        val cfg = pwmOnly.copy(attackSeconds = 1f)
        val e = SafetyEngine()
        e.step(tel(0, pwm = 0.1f), 0, cfg)
        val out = e.step(tel(100, pwm = 0.9f), 100, cfg)
        assertTrue(out.usage < 0.3f)
    }

    // ---- perda de sinal ---------------------------------------------------------------------

    @Test fun noTelemetryYieldsFaultNotGreen() {
        val out = SafetyEngine().step(null, 0)
        assertEquals(SafetyStatus.NO_SIGNAL, out.status)
        assertEquals(Rgb(255, 150, 0), out.frame.color)
        assertEquals(0f, out.margin)
    }

    @Test fun staleTelemetryAfterTimeoutBecomesFault() {
        val e = SafetyEngine()
        val t = tel(0, pwm = 0.2f)
        assertEquals(SafetyStatus.NORMAL, e.step(t, 1500).status)   // exatamente no limite ainda vale
        val out = e.step(t, 1501)
        assertEquals(SafetyStatus.NO_SIGNAL, out.status)
        assertNotEquals(Rgb(0, 220, 60), out.frame.color)
    }

    @Test fun faultPulsesButNeverGoesDark() {
        val e = SafetyEngine()
        val b = (0L until 1200L step 50L).map { e.step(null, it).frame.brightness }
        assertTrue(b.min() >= 255 / 4 - 1)
        assertTrue(b.max() > b.min())
    }

    @Test fun faultStyleSolidAndFastFlash() {
        val solid = SafetyEngine().step(null, 123, pwmOnly.copy(fault = FaultConfig(FaultStyle.SOLID)))
        assertEquals(255, solid.frame.brightness)
        val e = SafetyEngine()
        val flash = pwmOnly.copy(fault = FaultConfig(FaultStyle.FAST_FLASH))
        assertEquals(255, e.step(null, 0, flash).frame.brightness)
        assertEquals(0, e.step(null, 200, flash).frame.brightness)
    }

    @Test fun recoveryAfterSignalLossDoesNotInheritOldSmoothedValue() {
        val cfg = pwmOnly.copy(releaseSeconds = 10f)
        val e = SafetyEngine()
        e.step(tel(0, pwm = 0.95f), 0, cfg)
        e.step(null, 5000, cfg)
        val out = e.step(tel(5100, pwm = 0.2f), 5100, cfg)
        assertEquals(0.2f, out.usage, 1e-4f)
        assertFalse(out.flashing)
    }

    // ---- modos ------------------------------------------------------------------------------

    @Test fun speedModeUsesPilotLimit() {
        val cfg = SafetyConfig(mode = SafetyMode.SPEED, speedLimitKmh = 40f, releaseSeconds = 0f)
        val out = SafetyEngine().step(tel(0, pwm = 0.1f, speed = 32f), 0, cfg)
        assertEquals(0.8f, out.usage, 1e-4f)
        assertEquals(LimitingFactor.SPEED, out.limiting)
    }

    @Test fun speedLimitFallsBackToProfileMax() {
        val cfg = SafetyConfig(mode = SafetyMode.SPEED, releaseSeconds = 0f)
        val out = SafetyEngine().step(tel(0, speed = 25f), 0, cfg)
        assertEquals(0.5f, out.usage, 1e-4f)
    }

    @Test fun combinedPicksWorstFactor() {
        val cfg = SafetyConfig(mode = SafetyMode.COMBINED, speedLimitKmh = 40f, releaseSeconds = 0f)
        val pwmWorst = SafetyEngine().step(tel(0, pwm = 0.85f, speed = 10f), 0, cfg)
        assertEquals(LimitingFactor.PWM, pwmWorst.limiting)
        val speedWorst = SafetyEngine().step(tel(0, pwm = 0.2f, speed = 38f), 0, cfg)
        assertEquals(LimitingFactor.SPEED, speedWorst.limiting)
        assertEquals(0.95f, speedWorst.usage, 1e-4f)
    }

    @Test fun combinedIncludesLowBattery() {
        val cfg = SafetyConfig(mode = SafetyMode.COMBINED, releaseSeconds = 0f, batteryFilterSeconds = 0f)
        val ok = SafetyEngine().step(tel(0, pwm = 0.1f, speed = 5f, battery = 50f), 0, cfg)
        assertEquals(0.1f, ok.usage, 1e-4f)
        val low = SafetyEngine().step(tel(0, pwm = 0.1f, speed = 5f, battery = 16.5f), 0, cfg)
        assertEquals(LimitingFactor.BATTERY, low.limiting)
        assertEquals(0.8f, low.usage, 1e-3f)       // meio do caminho entre warn (25) e crítico (8)
        val empty = SafetyEngine().step(tel(0, pwm = 0.1f, speed = 5f, battery = 5f), 0, cfg)
        assertTrue(empty.flashing)
    }

    @Test fun batteryFactorCanBeDisabled() {
        val cfg = SafetyConfig(mode = SafetyMode.COMBINED, batteryFactor = false, releaseSeconds = 0f)
        assertEquals(0.1f, SafetyEngine().step(tel(0, pwm = 0.1f, speed = 5f, battery = 3f), 0, cfg).usage, 1e-4f)
    }

    @Test fun batteryFromVoltageWhenNotReported() {
        val cfg = SafetyConfig(mode = SafetyMode.COMBINED, releaseSeconds = 0f, batteryFilterSeconds = 0f)
        val t = tel(0, pwm = 0.1f, speed = 5f, battery = null, voltage = 53.5f)
        assertEquals(LimitingFactor.BATTERY, SafetyEngine().step(t, 0, cfg).limiting)
    }

    @Test fun batteryFilterIgnoresShortVoltageSag() {
        val cfg = SafetyConfig(mode = SafetyMode.COMBINED, releaseSeconds = 0f, batteryFilterSeconds = 5f)
        val e = SafetyEngine()
        var now = 0L
        repeat(10) { e.step(tel(now, pwm = 0.1f, speed = 5f, battery = 40f), now, cfg); now += 100 }
        val sag = e.step(tel(now, pwm = 0.1f, speed = 5f, battery = 10f), now, cfg)
        assertEquals(0.1f, sag.usage, 1e-4f)   // 0,1 s de afundamento mal mexe no filtro de 5 s
    }

    @Test fun barModeReportsFractionAndRamp() {
        val cfg = SafetyConfig(mode = SafetyMode.BAR, releaseSeconds = 0f)
        val out = SafetyEngine().step(tel(0, pwm = 0.5f), 0, cfg)
        assertEquals(0.5f, out.frame.bar!!.fraction, 1e-4f)
        val px = out.frame.render(10)
        assertEquals(5, px.count { it != Rgb.BLACK })
        assertEquals(Rgb.BLACK, px[9])
    }

    // ---- extras -----------------------------------------------------------------------------

    @Test fun restPulseModulatesBrightnessWhenStopped() {
        val cfg = pwmOnly.copy(restPulse = RestPulseConfig(enabled = true))
        val e = SafetyEngine()
        val values = (0L until 3000L step 100L).map { e.step(tel(it, pwm = 0.0f, speed = 0f), it, cfg).frame.brightness }
        assertTrue(values.min() < values.max())
        assertTrue(values.min() >= (255 * 0.35f).toInt() - 2)
    }

    @Test fun restPulseDoesNotApplyWhileMoving() {
        val cfg = pwmOnly.copy(restPulse = RestPulseConfig(enabled = true))
        val e = SafetyEngine()
        val values = (0L until 3000L step 100L).map { e.step(tel(it, pwm = 0.2f, speed = 15f), it, cfg).frame.brightness }
        assertEquals(setOf(255), values.toSet())
    }

    @Test fun fixedColorBelowSpeedOnlyWhenThereIsMargin() {
        val blue = Rgb(0, 120, 255)
        val cfg = pwmOnly.copy(fixedColor = FixedColorConfig(enabled = true, belowKmh = 5f, color = blue))
        assertEquals(blue, SafetyEngine().step(tel(0, pwm = 0.1f, speed = 2f), 0, cfg).frame.color)
        // alta carga mesmo devagar (ex.: subida forte): a segurança vence a cor fixa
        assertNotEquals(blue, SafetyEngine().step(tel(0, pwm = 0.75f, speed = 2f), 0, cfg).frame.color)
        assertNotEquals(blue, SafetyEngine().step(tel(0, pwm = 0.1f, speed = 12f), 0, cfg).frame.color)
    }

    @Test fun brakeFlashOnStrongRegen() {
        val cfg = pwmOnly.copy(brake = BrakeConfig(enabled = true, regenCurrentA = 20f))
        val e = SafetyEngine()
        val out = e.step(tel(0, pwm = 0.2f, current = -30f), 0, cfg)
        assertTrue(out.braking)
        assertEquals(Rgb.WHITE, out.frame.color)
        val later = e.step(tel(1000, pwm = 0.2f, current = 5f), 1000, cfg)
        assertFalse(later.braking)
    }

    @Test fun brakeFlashOnHardDeceleration() {
        val cfg = pwmOnly.copy(brake = BrakeConfig(enabled = true, decelKmhPerS = 12f))
        val e = SafetyEngine()
        e.step(tel(0, pwm = 0.2f, speed = 30f, current = 0f), 0, cfg)
        val out = e.step(tel(100, pwm = 0.2f, speed = 28f, current = 0f), 100, cfg)  // 20 km/h/s
        assertTrue(out.braking)
    }

    @Test fun criticalAlertWinsOverBrakeFlash() {
        val cfg = pwmOnly.copy(brake = BrakeConfig(enabled = true, regenCurrentA = 20f))
        val out = SafetyEngine().step(tel(0, pwm = 0.95f, current = -40f), 0, cfg)
        assertTrue(out.flashing)
        assertFalse(out.braking)
        assertEquals(Rgb(255, 0, 0), out.frame.color)
    }

    // ---- bordas -----------------------------------------------------------------------------

    @Test fun outOfRangeAndInvalidValuesAreClamped() {
        assertEquals(1f, SafetyEngine().step(tel(0, pwm = 3.5f), 0).usage)
        assertEquals(0f, SafetyEngine().step(tel(0, pwm = -0.4f), 0).usage)
        assertEquals(0f, SafetyEngine().step(tel(0, pwm = Float.NaN), 0).usage)
    }

    @Test fun zeroVoltageWithoutPwmDoesNotCrash() {
        val out = SafetyEngine().step(tel(0, pwm = null, voltage = 0f, speed = 0f), 0)
        assertEquals(0f, out.usage)
    }

    @Test fun backwardsClockDoesNotBreakFilter() {
        val e = SafetyEngine()
        e.step(tel(1000, pwm = 0.9f), 1000)
        val out = e.step(tel(900, pwm = 0.2f), 900)   // relógio voltou
        assertTrue(out.usage in 0f..1f)
    }

    @Test fun brightnessConfigIsRespected() {
        val out = SafetyEngine().step(tel(0, pwm = 0.2f), 0, pwmOnly.copy(brightness = 90))
        assertEquals(90, out.frame.brightness)
    }

    @Test fun profileBandsAreEditable() {
        val custom = profile.copy(
            pwmStops = listOf(ColorStop(0f, Rgb(0, 0, 255)), ColorStop(1f, Rgb(255, 0, 255))),
            flashAbovePwm = 0.5f,
        )
        val out = SafetyEngine().step(tel(0, pwm = 0.6f), 0, p = custom)
        assertTrue(out.flashing)
        assertEquals(Rgb(255, 0, 255), out.frame.color)
    }
}
