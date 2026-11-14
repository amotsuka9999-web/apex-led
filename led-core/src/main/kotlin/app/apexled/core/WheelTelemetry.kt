package app.apexled.core

import app.apexled.pattern.WheelSample

/**
 * One reading from the wheel, in display units, as the host app (WheelLog) provides it.
 * Optional values are null when the wheel does not report them.
 *
 * @param pwmPercent motor PWM / duty, 0..100.
 * @param appAlarm true while the host app itself is raising one of its own alarms.
 */
data class WheelTelemetry(
    val speedKmh: Double,
    val pwmPercent: Double,
    val temperatureC: Double? = null,
    val currentA: Double? = null,
    val voltageV: Double? = null,
    val appAlarm: Boolean = false,
) {
    fun toSample() = WheelSample(speedKmh, pwmPercent)
}
