package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.SpokenAnnouncement

/** A spoken alert that is due on this frame. */
data class AnnouncementCandidate(
    val announcement: SpokenAnnouncement,
    val priority: Int,
)

/**
 * Decides which spoken announcement is released, and when.
 *
 * Speech never mixes with a tone: a phrase over a PWM alarm is two signals smeared into noise.
 * So an announcement that comes due while something is sounding waits.
 *
 * What it waits for is not a timer. Waiting is bounded by meaning instead: every frame the
 * engine hands over the announcements whose conditions still hold, and this class keeps only
 * those. An announcement whose condition has fallen away is simply absent from the next list
 * and disappears - "speed eighty" does not survive the rider braking to forty. One that still
 * holds keeps its place, with its value refreshed from the newest frame, so it describes the
 * moment it is finally spoken rather than the moment it was queued. There is no staleness
 * timeout because a re-valued announcement cannot go stale.
 *
 * Waiting announcements are kept one per alert, not one per firing. Without that, an alert
 * stepping every kilometre would pile up four entries during a four-minute alarm and then say
 * "trip four" four times over, because all four would re-value to the same number.
 */
class AnnouncementArbiter {

    private data class Pending(
        val candidate: AnnouncementCandidate,
        val queuedAtMs: Long,
    )

    private val pending = mutableMapOf<String, Pending>()

    // Set by the platform's speech output, because speech runs on the wall clock while this
    // engine runs on telemetry time. Without it the engine would release a new announcement on
    // every frame and the platform would have to build a queue of its own - which is exactly
    // the monologue this class exists to shape.
    private var speaking = false

    /**
     * @param candidates every spoken alert whose condition holds on this frame and which has
     *   not spoken yet, with the metric value read from this frame.
     * @param toneSounding whether the single tone slot is occupied.
     * @return the announcement to speak now, or null.
     */
    fun select(
        candidates: List<AnnouncementCandidate>,
        toneSounding: Boolean,
        nowMs: Long,
    ): SpokenAnnouncement? {
        val alive = candidates.associateBy { it.announcement.alertId }

        // Anything no longer offered has had its condition fall away: drop it silently.
        pending.keys.retainAll(alive.keys)

        for ((alertId, candidate) in alive) {
            val existing = pending[alertId]
            pending[alertId] = Pending(
                candidate = candidate,
                // Keep the original queue time so a long wait does not lose its place in line.
                queuedAtMs = existing?.queuedAtMs ?: nowMs,
            )
        }

        if (toneSounding || speaking || pending.isEmpty()) return null

        // Priority 0 is the most critical. Then first come, first served; the alert id only
        // breaks a complete tie deterministically.
        val next = pending.values.minWith(
            compareBy(
                { it.candidate.priority },
                { it.queuedAtMs },
                { it.candidate.announcement.alertId },
            )
        )
        pending.remove(next.candidate.announcement.alertId)
        return next.candidate.announcement
    }

    /** Reported by the platform's speech output when it starts and finishes a phrase. */
    fun setSpeaking(value: Boolean) {
        speaking = value
    }

    fun clear() {
        pending.clear()
        speaking = false
    }
}
