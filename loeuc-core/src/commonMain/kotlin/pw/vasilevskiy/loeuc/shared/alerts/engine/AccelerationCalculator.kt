package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.AccelerationCurve
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import kotlin.math.exp
import kotlin.math.ln

/**
 * Turns a metric value into the repeat interval of an [AlertType.Accelerating] alert.
 */
object AccelerationCalculator {

    /**
     * Where the current value sits between the trigger threshold and the full-ramp threshold,
     * as 0..1.
     */
    fun progressFor(currentValue: Double, minTarget: Double, maxTarget: Double): Double {
        if (maxTarget <= minTarget) return 1.0
        return ((currentValue - minTarget) / (maxTarget - minTarget)).coerceIn(0.0, 1.0)
    }

    /**
     * Bends linear progress by the configured curve. Separate because the same curve shapes
     * both the interval and the pitch, see [AlertCadence].
     */
    fun applyCurve(curve: AccelerationCurve, ratio: Double): Double {
        val r = ratio.coerceIn(0.0, 1.0)
        return when (curve) {
            AccelerationCurve.LINEAR -> r
            AccelerationCurve.EXPONENTIAL -> (exp(r * 2.0) - 1.0) / (exp(2.0) - 1.0)
            AccelerationCurve.LOGARITHMIC -> ln(1.0 + r * 9.0) / ln(10.0)
        }
    }

    /**
     * The repeat interval in milliseconds for a metric value and its thresholds.
     *
     * It does not apply the curve itself: it converts the metric to 0..1 progress through
     * [progressFor] and hands that to [AlertCadence.forProgress], which decides the interval.
     * When the cadence crosses into a continuous tone [AlertCadence] returns `intervalMs = 0`,
     * and the final `coerceIn` here deliberately folds that zero back up to
     * [AlertType.Accelerating.minIntervalMs] - this function's older contract, for callers that
     * know nothing about the `continuous` flag and expect a positive interval.
     *
     * @param type the accelerating alert's configuration.
     * @param currentValue the metric's current value.
     * @param minTarget the threshold where the alert first fires (interval = maxIntervalMs).
     * @param maxTarget the threshold where the ramp is complete (interval = minIntervalMs).
     */
    fun calculateIntervalMs(
        type: AlertType.Accelerating,
        currentValue: Double,
        minTarget: Double,
        maxTarget: Double
    ): Long {
        if (maxTarget <= minTarget) return type.minIntervalMs
        val progress = progressFor(currentValue, minTarget, maxTarget)
        // Bounds sorted ascending, for the same reason as in [AlertCadence.forProgress]: an
        // inverted interval in a saved alert must not crash the application.
        return AlertCadence.forProgress(type, progress).intervalMs
            .coerceIn(
                minOf(type.minIntervalMs, type.maxIntervalMs),
                maxOf(type.minIntervalMs, type.maxIntervalMs)
            )
    }
}
