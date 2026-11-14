package app.apexled.core

import app.apexled.pattern.WheelSample
import kotlin.math.exp

/**
 * Low-pass filter for the values that drive the colour, so sensor jitter does not make the
 * light shimmer. Exponential smoothing with time constant [timeConstantMs]; 0 disables it.
 *
 * Only cosmetic output goes through here. Critical detection always reads raw telemetry.
 */
class InputSmoother {
    private var value: WheelSample? = null
    private var updatedAt = 0L

    fun update(sample: WheelSample, timeConstantMs: Long, nowMs: Long): WheelSample {
        val previous = value
        val next = if (previous == null || timeConstantMs <= 0 || !sample.isFinite()) {
            if (sample.isFinite()) sample else previous ?: WheelSample.ZERO
        } else {
            val elapsed = (nowMs - updatedAt).coerceAtLeast(0)
            val alpha = 1.0 - exp(-elapsed.toDouble() / timeConstantMs)
            WheelSample(
                speedKmh = previous.speedKmh + (sample.speedKmh - previous.speedKmh) * alpha,
                pwmPercent = previous.pwmPercent + (sample.pwmPercent - previous.pwmPercent) * alpha,
            )
        }
        value = next
        updatedAt = nowMs
        return next
    }

    fun reset() {
        value = null
    }

    private fun WheelSample.isFinite() = speedKmh.isFinite() && pwmPercent.isFinite()
}
