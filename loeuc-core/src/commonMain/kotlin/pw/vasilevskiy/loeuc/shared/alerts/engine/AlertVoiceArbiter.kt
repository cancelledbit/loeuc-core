package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice

/** A contender for the single voice slot on this frame. */
data class VoiceCandidate(
    val voice: AlertVoice,
    val priority: Int,
)

/**
 * Owner of the one slot: exactly one alert sounds at a time.
 *
 * It replaced a priority queue that rotated equally critical alerts round-robin by how long
 * ago each had sounded. Riders heard that interleaving of patterns as noise they could not
 * read.
 *
 * @param minHoldMs how long the winner keeps the slot at minimum. Without it two equally
 *   critical alerts whose metrics hover around their thresholds would swap the slot frame by
 *   frame - the same noise from the other end. A strictly higher priority still cuts in
 *   immediately, without waiting for the hold to expire.
 */
class AlertVoiceArbiter(private val minHoldMs: Long = 300L) {

    private var heldVoice: AlertVoice? = null
    private var heldPriority: Int = Int.MAX_VALUE
    private var holdUntilMs: Long = 0L

    fun select(candidates: List<VoiceCandidate>, nowMs: Long): AlertVoice? {
        if (candidates.isEmpty()) {
            clear()
            return null
        }

        // Priority 0 is the most critical. The second key means that between two equally
        // critical alerts the one further along its own curve sounds. The third exists only to
        // make a complete tie deterministic.
        val best = candidates.minWith(
            compareBy({ it.priority }, { -it.voice.progress }, { it.voice.alertId })
        )

        val held = heldVoice
        val stillMatching = held?.let { h -> candidates.firstOrNull { it.voice.alertId == h.alertId } }

        if (stillMatching != null && nowMs < holdUntilMs && best.priority >= heldPriority) {
            // The slot is held, but the voice state is refreshed: an alert that holds the
            // slot has to keep ramping while it does.
            heldVoice = stillMatching.voice
            return stillMatching.voice
        }

        heldVoice = best.voice
        heldPriority = best.priority
        holdUntilMs = nowMs + maxOf(minHoldMs, best.voice.pattern.totalDurationMs())
        return best.voice
    }

    fun clear() {
        heldVoice = null
        heldPriority = Int.MAX_VALUE
        holdUntilMs = 0L
    }
}
