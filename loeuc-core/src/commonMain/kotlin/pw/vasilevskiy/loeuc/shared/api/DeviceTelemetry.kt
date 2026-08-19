package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot

/**
 * Telemetry values decoded so far, keyed by [DeviceMetric] rather than by brand-specific field
 * names.
 *
 * A map rather than a flat data class of sixty fields: adding a metric is a new enum constant,
 * not a new constructor parameter, so neither binary compatibility nor Swift interop suffers.
 */
class DeviceTelemetry private constructor(val values: Map<DeviceMetric, Double>) {

    /** The value, or null when the device has not reported this metric. */
    operator fun get(metric: DeviceMetric): Double? = values[metric]

    /**
     * Folds [newer] on top of this one. Metrics [newer] does not carry are kept: a frame that
     * omits a field says nothing about it, it does not say the field is gone.
     */
    fun mergedWith(newer: DeviceTelemetry): DeviceTelemetry =
        DeviceTelemetry(values + newer.values)

    /**
     * Projects onto the sixteen [MetricId] values the alert engine understands.
     *
     * @param timestampMs the device's clock, never the phone's - `AlertEngine` reads durations
     *   and cooldowns from it, which is what makes accelerated dump playback behave like a ride.
     */
    fun toSnapshot(timestampMs: Long): TelemetrySnapshot =
        TelemetrySnapshot(timestampMs, MetricIdSources.project(values))

    companion object {
        fun of(vararg pairs: Pair<DeviceMetric, Double>): DeviceTelemetry =
            DeviceTelemetry(pairs.filter { (_, value) -> value.isFinite() }.toMap())
    }
}
