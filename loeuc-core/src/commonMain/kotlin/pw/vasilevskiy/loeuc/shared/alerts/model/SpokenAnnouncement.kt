package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * One sentence the app is about to say, with its number already resolved.
 *
 * The value is captured at the moment the engine releases the announcement, not at the moment
 * the alert fired. That is the whole point of the waiting rule: an announcement held back
 * while an alarm sounded is re-checked and re-valued before it speaks, so it describes now
 * rather than a moment that has passed. "Speed eighty" does not survive the rider braking to
 * forty; "battery ten" that waited out a long alarm comes out as "battery eight".
 *
 * @param alertId the alert this came from, so the platform can drop a repeat of one already
 *   speaking.
 * @param phrase the rider's template, still with its placeholders.
 * @param metric the metric [value] belongs to; it sets the rounding and the unit word.
 * @param value the metric's value at the moment of release.
 */
data class SpokenAnnouncement(
    val alertId: String,
    val phrase: String,
    val metric: MetricId,
    val value: Double,
) {
    /**
     * The sentence to hand to the synthesizer.
     *
     * Takes the language explicitly: the phrase is the rider's own text, but the unit word and
     * the decimal separator have to agree with it.
     */
    fun text(isRu: Boolean): String = AnnouncementComposer.compose(phrase, metric, value, isRu)
}
