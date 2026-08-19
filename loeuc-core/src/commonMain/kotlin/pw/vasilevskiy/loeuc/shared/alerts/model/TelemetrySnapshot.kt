package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Metric values read from a wheel at one point in time.
 */
data class TelemetrySnapshot(
    val timestampMs: Long,
    val metrics: Map<MetricId, Double> = emptyMap()
) {
    fun getValue(metric: MetricId): Double? = metrics[metric]

    fun withMetric(metric: MetricId, value: Double): TelemetrySnapshot {
        val updated = metrics.toMutableMap()
        updated[metric] = value
        return copy(metrics = updated)
    }

    companion object {
        fun create(timestampMs: Long, vararg pairs: Pair<MetricId, Double>): TelemetrySnapshot {
            return TelemetrySnapshot(timestampMs, pairs.toMap())
        }
    }
}
