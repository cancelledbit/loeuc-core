package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType

/**
 * A point on the ramp: how often to repeat the pattern, how far to raise the pitch, and
 * whether it is time to simply hold the gate open.
 */
data class CadencePoint(
    val intervalMs: Long,
    val pitchRatio: Double,
    val continuous: Boolean,
)

/**
 * Turns 0..1 progress into a cadence.
 *
 * The idea the whole ramp rests on: a continuous tone is not "very frequent beeps", it is a
 * 100% duty cycle. That is why [CadencePoint.continuous] is its own flag rather than a
 * consequence of a small interval - shrink the gap as far as you like, while it is above zero
 * there is still a seam between beeps, and a seam is audible.
 */
object AlertCadence {

    fun forProgress(type: AlertType.Accelerating, progress: Double): CadencePoint {
        val clamped = progress.coerceIn(0.0, 1.0)
        val curved = AccelerationCalculator.applyCurve(type.curve, clamped)
        val continuous = clamped >= type.continuousFromRatio

        // The bounds are sorted rather than taken as written. `coerceIn` throws
        // IllegalArgumentException when the minimum exceeds the maximum, and on Kotlin/Native
        // an exception here is not an error, it is the death of the process. Inverted
        // intervals do arrive from saved alerts - an editor that never checked one against the
        // other wrote them - and they have to be tolerated here rather than fixed upstream,
        // because the data is already on riders' phones.
        val lowMs = minOf(type.minIntervalMs, type.maxIntervalMs)
        val highMs = maxOf(type.minIntervalMs, type.maxIntervalMs)
        val intervalMs = if (continuous) {
            0L
        } else {
            val span = (highMs - lowMs).toDouble()
            (highMs.toDouble() - curved * span)
                .toLong()
                .coerceIn(lowMs, highMs)
        }

        val pitchRatio = 1.0 + curved * (type.pitchRiseRatio - 1.0)

        return CadencePoint(intervalMs, pitchRatio, continuous)
    }
}
