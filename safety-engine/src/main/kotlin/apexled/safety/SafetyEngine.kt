package apexled.safety

import apexled.core.ColorScale
import apexled.core.LedFrame
import apexled.core.ColorStop
import apexled.core.Rgb
import apexled.core.Telemetry
import apexled.core.WheelProfile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class SafetyLevel(val label: String) {
    OK("Margem ampla"), ATTENTION("Atenção"), WARNING("Cuidado"), ALERT("Alerta"), CRITICAL("Margem esgotada"),
}

enum class LimitingFactor(val label: String) { PWM("PWM"), SPEED("Velocidade"), BATTERY("Bateria") }

enum class SafetyStatus { NORMAL, NO_SIGNAL }

data class SafetyOutput(
    val frame: LedFrame,
    val status: SafetyStatus,
    /** Uso suavizado 0..1 (1 = sem margem). 1 também quando não há sinal. */
    val usage: Float,
    /** 1 - uso. */
    val margin: Float,
    val level: SafetyLevel,
    val limiting: LimitingFactor?,
    val flashing: Boolean,
    val braking: Boolean = false,
    /** Em repouso (parado/devagar com folga): a fita mostra o "stand-by" escolhido. */
    val resting: Boolean = false,
)

/**
 * Motor de segurança: telemetria + perfil + configuração → quadro de LED. Lógica pura e determinística:
 * o tempo entra por parâmetro ([nowMs]), então é testável sem relógio.
 *
 * Regras de robustez:
 *  - Subida rápida, descida lenta (filtro exponencial assimétrico).
 *  - Histerese ao descer de faixa e ao parar de piscar, para a cor não cintilar.
 *  - Sem telemetria nova por [SafetyConfig.signalTimeoutMs] → estado de falha explícito (nunca "verde mentiroso").
 *
 * Não é thread-safe: chame de uma única corrotina/thread.
 */
class SafetyEngine {
    private var smoothed = 0f
    private var lastNowMs = -1L
    private var hadSignal = false
    private var stepIndex = 0
    private var levelIndex = 0
    private var flashLatched = false
    private var batteryFiltered: Float? = null

    private var prevSampleTs = -1L
    private var prevSampleSpeed = 0f
    private var brakeUntilMs = Long.MIN_VALUE

    fun reset() {
        smoothed = 0f; lastNowMs = -1; hadSignal = false; stepIndex = 0; levelIndex = 0
        flashLatched = false; batteryFiltered = null; prevSampleTs = -1; brakeUntilMs = Long.MIN_VALUE
        resting = true
    }

    /** Repouso com histerese: entra abaixo de belowKmh, sai acima de belowKmh + 2 km/h (ou ao subir o uso). */
    private var resting = true

    fun update(
        telemetry: Telemetry?,
        nowMs: Long,
        profile: WheelProfile,
        config: SafetyConfig,
    ): SafetyOutput {
        val dt = if (lastNowMs < 0) 0f else ((nowMs - lastNowMs).coerceIn(0L, 2000L)) / 1000f
        lastNowMs = nowMs

        val stale = telemetry == null || nowMs - telemetry.timestampMs > config.signalTimeoutMs
        if (telemetry == null || stale) {
            hadSignal = false
            batteryFiltered = null
            return faultOutput(nowMs, config)
        }

        val raw = rawUsage(telemetry, profile, config, dt)
        if (!hadSignal) {
            // Primeira amostra válida (ou volta do sinal): sem herdar valor antigo.
            smoothed = raw.usage
            hadSignal = true
            levelIndex = 0
            stepIndex = 0
            flashLatched = false
        } else {
            val tau = if (raw.usage > smoothed) config.attackSeconds else config.releaseSeconds
            val alpha = if (tau <= 0f) 1f else 1f - exp(-dt / tau)
            smoothed += alpha * (raw.usage - smoothed)
        }
        val usage = smoothed.coerceIn(0f, 1f)

        updateLevel(usage, profile, config)
        val level = SafetyLevel.entries[levelIndex]

        val flashOn = updateFlashLatch(usage, profile, config)
        val braking = updateBrake(telemetry, nowMs, config)

        val barMode = config.mode == SafetyMode.BAR
        val baseColor = pickColor(usage, profile, config)
        var color = baseColor
        var brightness = config.brightness.coerceIn(0, 255)

        // Cor fixa em baixa velocidade: só quando há folga.
        val fc = config.fixedColor
        resting = fc.enabled && usage < ATTENTION_AT &&
            telemetry.speedKmh < fc.belowKmh + (if (resting) REST_HYSTERESIS_KMH else 0f)
        if (resting) color = fc.colorAt(nowMs)
        // Pulso lento parado.
        if (config.restPulse.enabled && telemetry.speedKmh < config.restPulse.belowKmh && !flashOn) {
            val phase = 0.5f + 0.5f * sin((2.0 * PI * (nowMs % config.restPulse.periodMs) / config.restPulse.periodMs).toFloat())
            val k = config.restPulse.minBrightnessFraction + (1f - config.restPulse.minBrightnessFraction) * phase
            brightness = (brightness * k).toInt()
        }
        var flashing = false
        if (flashOn) {
            // Alerta crítico: cor do topo da escala, piscando em cheio. Prevalece sobre tudo.
            color = ColorScale.colorAt(activeStops(profile, config), 1f, stepped = false)
            flashing = true
            if ((nowMs % config.flashPeriodMs) >= config.flashPeriodMs / 2) brightness = 0
        } else if (braking) {
            color = Rgb.WHITE
            if ((nowMs % config.brake.flashPeriodMs) >= config.brake.flashPeriodMs / 2) brightness = 0
        }

        val bar = if (barMode) LedFrame.Bar(usage, activeStops(profile, config)) else null
        return SafetyOutput(
            frame = LedFrame(color, brightness, bar),
            status = SafetyStatus.NORMAL,
            usage = usage,
            margin = 1f - usage,
            level = level,
            limiting = raw.limiting,
            flashing = flashing,
            braking = braking && !flashing,
            resting = resting && !flashing && !braking,
        )
    }

    // ---- fatores -------------------------------------------------------------------------------

    private data class Raw(val usage: Float, val limiting: LimitingFactor?)

    private fun rawUsage(t: Telemetry, profile: WheelProfile, config: SafetyConfig, dt: Float): Raw {
        val pwm = pwmUsage(t, profile)
        val speed = speedUsage(t, profile, config)
        return when (config.mode) {
            SafetyMode.PWM, SafetyMode.BAR -> Raw(pwm, LimitingFactor.PWM)
            SafetyMode.SPEED -> Raw(speed, LimitingFactor.SPEED)
            SafetyMode.COMBINED -> {
                val battery = if (config.batteryFactor) batteryUsage(t, profile, config, dt) else 0f
                val worst = maxOf(pwm, speed, battery)
                val limiting = when {
                    worst <= 0f -> null
                    worst == pwm -> LimitingFactor.PWM
                    worst == speed -> LimitingFactor.SPEED
                    else -> LimitingFactor.BATTERY
                }
                Raw(worst, limiting)
            }
        }
    }

    private fun pwmUsage(t: Telemetry, profile: WheelProfile): Float {
        val pwm = t.pwm ?: profile.pwmCalibration.estimate(t.speedKmh, t.voltage)
        return sane(pwm)
    }

    private fun speedLimit(profile: WheelProfile, config: SafetyConfig): Float = config.speedLimitKmh
        ?: profile.tiltbackSpeedKmh.takeIf { it > 0f }
        ?: profile.maxSpeedKmh

    /** Escala em km/h do modo Velocidade, se o piloto definiu uma. */
    private fun ownSpeedScale(config: SafetyConfig): List<ColorStop>? =
        config.speedStops?.takeIf { config.mode == SafetyMode.SPEED && it.size >= 2 }

    /** Escala de cores em uso, sempre em fração do uso (0..1): a de km/h é dividida pelo limite. */
    private fun activeStops(profile: WheelProfile, config: SafetyConfig): List<ColorStop> {
        val own = ownSpeedScale(config) ?: return profile.pwmStops
        val limit = speedLimit(profile, config).coerceAtLeast(1f)
        return own.map { ColorStop(it.at / limit, it.color) }
    }

    private fun speedUsage(t: Telemetry, profile: WheelProfile, config: SafetyConfig): Float {
        val limit = speedLimit(profile, config)
        return if (limit > 0f) sane(t.speedKmh / limit) else 0f
    }

    /** Abaixo de "warn" o uso vai de 0,6 (atenção) a 1,0 (crítico). */
    private fun batteryUsage(t: Telemetry, profile: WheelProfile, config: SafetyConfig, dt: Float): Float {
        val pct = t.batteryPercent ?: profile.batteryPercentFor(t.voltage)
        if (pct.isNaN()) return 0f
        val prev = batteryFiltered
        val filtered = if (prev == null || config.batteryFilterSeconds <= 0f) pct
        else prev + (1f - exp(-dt / config.batteryFilterSeconds)) * (pct - prev)
        batteryFiltered = filtered
        val warn = profile.lowBatteryWarnPercent
        val crit = profile.lowBatteryCriticalPercent
        if (filtered >= warn) return 0f
        if (warn <= crit) return 1f
        val frac = ((warn - filtered) / (warn - crit)).coerceIn(0f, 1f)
        return ATTENTION_AT + (1f - ATTENTION_AT) * frac
    }

    private fun sane(v: Float?): Float = if (v == null || v.isNaN()) 0f else v.coerceIn(0f, 1f)

    // ---- faixas, histerese, pisca ---------------------------------------------------------------

    private fun thresholds(profile: WheelProfile) = floatArrayOf(ATTENTION_AT, 0.70f, 0.80f, profile.flashAbovePwm)

    private fun updateLevel(usage: Float, profile: WheelProfile, config: SafetyConfig) {
        val thr = thresholds(profile)
        val target = thr.count { usage >= it }
        if (target > levelIndex) levelIndex = target
        else while (levelIndex > 0 && usage < thr[levelIndex - 1] - config.hysteresis) levelIndex--
    }

    private fun updateFlashLatch(usage: Float, profile: WheelProfile, config: SafetyConfig): Boolean {
        val on = ownSpeedScale(config)?.let { config.speedFlashKmh?.let { k -> k / speedLimit(profile, config) } ?: 1f } ?: profile.flashAbovePwm
        flashLatched = if (flashLatched) usage >= on - config.hysteresis else usage >= on
        return flashLatched
    }

    private fun pickColor(usage: Float, profile: WheelProfile, config: SafetyConfig): Rgb {
        val stops = activeStops(profile, config).sortedBy { it.at }
        if (stops.isEmpty()) return Rgb.WHITE
        if (!config.stepped) return ColorScale.colorAt(stops, usage, stepped = false)
        val raw = stops.indexOfLast { it.at <= usage }.coerceAtLeast(0)
        if (raw > stepIndex) stepIndex = raw
        else while (stepIndex > 0 && usage < stops[stepIndex].at - config.hysteresis) stepIndex--
        stepIndex = stepIndex.coerceIn(0, stops.lastIndex)
        return stops[stepIndex].color
    }

    private fun updateBrake(t: Telemetry, nowMs: Long, config: SafetyConfig): Boolean {
        if (!config.brake.enabled) { prevSampleTs = t.timestampMs; prevSampleSpeed = t.speedKmh; return false }
        var triggered = (t.currentA ?: 0f) <= -config.brake.regenCurrentA
        if (prevSampleTs >= 0 && t.timestampMs > prevSampleTs) {
            val ds = (t.timestampMs - prevSampleTs) / 1000f
            if (ds in 0.03f..0.6f) {
                val decel = (prevSampleSpeed - t.speedKmh) / ds
                if (decel >= config.brake.decelKmhPerS) triggered = true
            }
        }
        if (t.timestampMs != prevSampleTs) { prevSampleTs = t.timestampMs; prevSampleSpeed = t.speedKmh }
        if (triggered) brakeUntilMs = nowMs + config.brake.holdMs
        return nowMs < brakeUntilMs
    }

    // ---- falha ---------------------------------------------------------------------------------

    private fun faultOutput(nowMs: Long, config: SafetyConfig): SafetyOutput {
        val f = config.fault
        val max = config.brightness.coerceIn(0, 255)
        val brightness = when (f.style) {
            FaultStyle.SOLID -> max
            FaultStyle.PULSE -> {
                val phase = 0.5f + 0.5f * sin((2.0 * PI * (nowMs % f.periodMs) / f.periodMs).toFloat())
                (max * (0.25f + 0.75f * phase)).toInt()
            }
            FaultStyle.FAST_FLASH -> if ((nowMs % 300L) < 150L) max else 0
        }
        return SafetyOutput(
            frame = LedFrame(f.color, brightness, null),
            status = SafetyStatus.NO_SIGNAL,
            usage = 1f,
            margin = 0f,
            level = SafetyLevel.CRITICAL,
            limiting = null,
            flashing = false,
        )
    }

    private companion object {
        const val REST_HYSTERESIS_KMH = 2f
        const val ATTENTION_AT = 0.60f
    }
}
