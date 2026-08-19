package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * [Alert.priority] is an Int in 0..10 where 0 is the most critical. Eleven colours are not
 * distinguishable at a glance, so priority collapses into three bands.
 *
 * A band carries a stroke pattern as well as a colour - solid, dashed, dotted - which is what
 * keeps the distinction readable to a colour-blind rider and in direct sunlight.
 *
 * Only the classification and the stroke geometry live here; colours belong to the platform.
 *
 * Sizes are in density-independent units: dp on Android, pt on iOS, same numbers. The field
 * names promise neither, because the library is consumed from both.
 *
 * @param strokeWidth width of the threshold line on a gauge
 * @param dashIntervals dash pattern; an empty list means a solid line
 * @param hatchesZone whether the zone beyond the threshold is hatched
 */
enum class SeverityBand(
    val strokeWidth: Float,
    val dashIntervals: List<Float>,
    val hatchesZone: Boolean,
) {
    Critical(strokeWidth = 1.5f, dashIntervals = emptyList(), hatchesZone = true),
    Warning(strokeWidth = 1.2f, dashIntervals = listOf(3f, 2.5f), hatchesZone = false),
    Info(strokeWidth = 1.0f, dashIntervals = listOf(1.5f, 2.5f), hatchesZone = false);

    companion object {
        fun forPriority(priority: Int): SeverityBand = when {
            priority <= 2 -> Critical
            priority <= 5 -> Warning
            else -> Info
        }

        /** The most critical band in the list, or null when the list is empty. */
        fun worst(bands: List<SeverityBand>): SeverityBand? = bands.minByOrNull { it.ordinal }
    }
}
