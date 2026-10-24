package pw.vasilevskiy.loeuc.shared.api

import kotlin.math.abs

/**
 * Tells a pack-voltage reading that cannot be true from a sag that is.
 *
 * ## Why
 *
 * A wheel switched off with its button may get one last frame out with a pack voltage far
 * below anything the pack can be at - riders see it on LeaperKim, and Lynx-S recordings carry
 * lone readings near zero among healthy frames. [TelemetryPlausibility] cannot catch it: the
 * number is a legal voltage, just not this pack's. Such a frame is still a fact about the wire
 * and is kept in the ride log; what it must not do is fire a voltage alert, teach the battery
 * estimator, or stand as the ride's worst sag.
 *
 * ## The rule
 *
 * A reading is a dropout when it is below [DropoutFraction] of the reference level **and** the
 * current in the same frame does not explain it. The reference is the median of the readings
 * accepted over the last [WindowMs]; a dropout never enters it, so a run of bad frames is judged
 * against the last good level, not against itself. Current is the tie-breaker the owner asked
 * for: a pack under a hundred amps sags a tenth, never a third, so a drop that deep with a
 * loaded motor is a wheel in trouble and passes through untouched. A drop with no current is
 * physics saying no. Unknown current counts as no current - an unexplained drop stays
 * unexplained. That is a deliberate choice for the one wire that carries no current at all
 * (Solowheel Xtreme): a third of the pack gone with nothing to show for it is not a sag there
 * either, and the alternative - trusting every reading on exactly the wheel that can least
 * vouch for it - is worse.
 *
 * The window is the safety valve. Once every accepted reading has aged out, there is no
 * reference and the next reading is accepted whatever it is, so a pack that really is that low
 * is silenced for ten seconds at most, not for the rest of the ride.
 *
 * Stateful and single-threaded by design: one instance per link, fed on the publish thread.
 */
class VoltageDropoutDetector {

    private val acceptedTimes = ArrayDeque<Long>()
    private val acceptedVolts = ArrayDeque<Double>()
    private var lastTimestampMs = Long.MIN_VALUE

    /**
     * Classifies one reading and, unless it is a dropout, adds it to the reference window.
     *
     * @param currentA any current the frame carries - phase current where the protocol has it in
     * the same frame, battery current otherwise; the same two, in the same order, that the ride
     * readers use, so a frame is classified alike live and read back. Sign is ignored. `null`
     * and NaN mean unknown.
     * @return true when the reading is a dropout. A missing or non-finite [voltage] is neither a
     * reading nor a dropout and leaves the window untouched.
     */
    fun isDropout(timestampMs: Long, voltage: Double?, currentA: Double?): Boolean {
        if (voltage == null || !voltage.isFinite()) return false
        // Time went backwards: a dump seek. Yesterday's reference is not a reference.
        if (timestampMs < lastTimestampMs) clear()
        lastTimestampMs = timestampMs
        evictBefore(timestampMs - WindowMs)
        val reference = median()
        val loaded = currentA != null && currentA.isFinite() && abs(currentA) >= LoadCurrentA
        if (reference != null && voltage < reference * DropoutFraction && !loaded) {
            return true
        }
        acceptedTimes.addLast(timestampMs)
        acceptedVolts.addLast(voltage)
        return false
    }

    fun clear() {
        acceptedTimes.clear()
        acceptedVolts.clear()
        lastTimestampMs = Long.MIN_VALUE
    }

    private fun evictBefore(cutoffMs: Long) {
        while (acceptedTimes.isNotEmpty() && acceptedTimes.first() < cutoffMs) {
            acceptedTimes.removeFirst()
            acceptedVolts.removeFirst()
        }
    }

    private fun median(): Double? {
        if (acceptedVolts.isEmpty()) return null
        val sorted = acceptedVolts.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    companion object {
        /** Below this share of the reference level a reading is not this pack's. */
        const val DropoutFraction = 0.7

        /** Current at or above this is load enough for any drop to be a real sag. */
        const val LoadCurrentA = 10.0

        /** How long an accepted reading stays in the reference. */
        const val WindowMs = 10_000L

        /**
         * The same rule over a recorded ride on a fixed grid: the voltage series with every
         * dropout blanked to `null`, so a report built from ride rows agrees with what the
         * live link would have said. [currentA] is the current series on the same grid, or
         * `null` when the ride has none.
         *
         * A grid is interpolated, and that is what [shoulderMillis] is for: between the last
         * good row and the dropout the resampler draws a straight line, and its upper part -
         * 130, 121, 112 V on the way from 139 to 50 - sits above the threshold while being
         * just as made up. Every point within [shoulderMillis] of a dropout is blanked with
         * it; pass the resampler's widest gap.
         */
        fun withoutDropouts(
            voltage: List<Double?>,
            currentA: List<Double?>?,
            stepMillis: Long,
            shoulderMillis: Long = 0L,
        ): List<Double?> {
            val detector = VoltageDropoutDetector()
            val dropout = BooleanArray(voltage.size)
            voltage.forEachIndexed { index, value ->
                dropout[index] = value != null &&
                    detector.isDropout(index * stepMillis, value, currentA?.getOrNull(index))
            }
            val radius = if (stepMillis > 0) ((shoulderMillis + stepMillis - 1) / stepMillis).toInt() else 0
            val blank = BooleanArray(voltage.size)
            dropout.forEachIndexed { index, isDropout ->
                if (!isDropout) return@forEachIndexed
                for (i in maxOf(0, index - radius)..minOf(voltage.lastIndex, index + radius)) blank[i] = true
            }
            return voltage.mapIndexed { index, value -> value?.takeUnless { blank[index] } }
        }
    }
}
