package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * One firing of an alert, produced by the alert engine.
 *
 * @param alert The alert that fired.
 * @param timestampMs Telemetry time at which the event was computed, in milliseconds.
 * @param currentIntervalMs Repeat interval for this firing: the computed one for Accelerating
 *   alerts, the base one otherwise.
 * @param triggeredMetrics The metrics that made the alert fire, with their current values.
 */
data class AlertTriggerEvent(
    val alert: Alert,
    val timestampMs: Long,
    val currentIntervalMs: Long = 0L,
    val triggeredMetrics: Map<MetricId, Double> = emptyMap()
)
