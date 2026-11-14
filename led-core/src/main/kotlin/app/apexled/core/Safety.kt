package app.apexled.core

import kotlin.math.abs

/** Why the critical indicator is on. */
enum class CriticalCause { APP_ALARM, PWM, SPEED, TEMPERATURE, CURRENT, VOLTAGE }

/**
 * When the wheel counts as critical. Lives apart from the pattern settings on purpose:
 * nothing in a pattern can switch the critical indicator off.
 *
 * An alarm raised by the host app is always critical and has no setting. The thresholds add
 * further causes; null means "not used". Which of them should be on by default is an open
 * question (docs/questoes.md, Q3), so none is.
 *
 * @param holdMs how long the indicator stays on after the last cause clears, so a value
 *   hovering around a threshold does not make the light flicker. It never delays the onset.
 */
data class SafetyConfig(
    val pwmPercent: Double? = null,
    val speedKmh: Double? = null,
    val temperatureC: Double? = null,
    val currentA: Double? = null,
    val voltageBelowV: Double? = null,
    val holdMs: Long = 1_500,
) {
    init {
        require(pwmPercent == null || pwmPercent in 1.0..100.0) { "pwmPercent must be 1..100" }
        require(speedKmh == null || speedKmh > 0.0) { "speedKmh must be positive" }
        require(temperatureC == null || temperatureC > 0.0) { "temperatureC must be positive" }
        require(currentA == null || currentA > 0.0) { "currentA must be positive" }
        require(voltageBelowV == null || voltageBelowV > 0.0) { "voltageBelowV must be positive" }
        require(holdMs in 0..MAX_HOLD_MS) { "holdMs must be 0..$MAX_HOLD_MS" }
    }

    /** The causes that are true for [telemetry] right now. Pure. */
    fun causes(telemetry: WheelTelemetry): Set<CriticalCause> = buildSet {
        if (telemetry.appAlarm) add(CriticalCause.APP_ALARM)
        if (pwmPercent != null && abs(telemetry.pwmPercent) >= pwmPercent) add(CriticalCause.PWM)
        if (speedKmh != null && abs(telemetry.speedKmh) >= speedKmh) add(CriticalCause.SPEED)
        if (temperatureC != null && telemetry.temperatureC != null && telemetry.temperatureC >= temperatureC) {
            add(CriticalCause.TEMPERATURE)
        }
        if (currentA != null && telemetry.currentA != null && abs(telemetry.currentA) >= currentA) {
            add(CriticalCause.CURRENT)
        }
        if (voltageBelowV != null && telemetry.voltageV != null && telemetry.voltageV <= voltageBelowV) {
            add(CriticalCause.VOLTAGE)
        }
    }

    companion object {
        const val MAX_HOLD_MS = 10_000L
    }
}

/** @param active whether the indicator must be shown; [causes] are the ones true right now (empty while only held). */
data class CriticalState(val active: Boolean, val causes: Set<CriticalCause>) {
    companion object {
        val CLEAR = CriticalState(false, emptySet())
    }
}

/** Tracks the critical state over time: immediate onset, held release. Not thread-safe; the controller serialises calls. */
class CriticalMonitor {
    private var lastCauseAt: Long? = null

    /** @param telemetry the current reading, or null when there is none (wheel not connected). */
    fun update(telemetry: WheelTelemetry?, config: SafetyConfig, nowMs: Long): CriticalState {
        val causes = telemetry?.let(config::causes).orEmpty()
        if (causes.isNotEmpty()) {
            lastCauseAt = nowMs
            return CriticalState(true, causes)
        }
        val last = lastCauseAt ?: return CriticalState.CLEAR
        if (nowMs - last < config.holdMs) return CriticalState(true, emptySet())
        lastCauseAt = null
        return CriticalState.CLEAR
    }
}
