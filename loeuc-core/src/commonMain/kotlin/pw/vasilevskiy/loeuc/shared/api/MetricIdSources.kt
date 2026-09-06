package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId

/**
 * Which [DeviceMetric] values feed each alert [MetricId].
 *
 * The list per id is a **preference order, not a set of synonyms**: `CURRENT_A` means battery
 * current, and reads output current only on wheels that report no battery current at all.
 */
object MetricIdSources {

    private val bySources: Map<MetricId, List<DeviceMetric>> = mapOf(
        MetricId.SPEED_KMH to listOf(DeviceMetric.SpeedKmh),
        MetricId.PWM_PERCENT to listOf(DeviceMetric.PwmPercent),
        // Charger quantities sit last under the same ids: a charger has no pack voltage, no
        // wheel power and no controller probe of its own, it fills in its own. Without these
        // entries every alert that matters while charging would otherwise lose its source metric.
        MetricId.VOLTAGE_V to listOf(DeviceMetric.PackVoltageV, DeviceMetric.ChargeVoltageV),
        MetricId.CELL_VOLTAGE_AVG_V to listOf(DeviceMetric.CellVoltageAvgV),
        MetricId.CELL_VOLTAGE_DELTA_V to listOf(DeviceMetric.CellVoltageDeltaV),
        MetricId.CURRENT_A to listOf(
            DeviceMetric.BatteryCurrentA,
            DeviceMetric.OutputCurrentA,
            DeviceMetric.ChargeCurrentA,
        ),
        MetricId.PHASE_CURRENT_A to listOf(DeviceMetric.PhaseCurrentA),
        MetricId.POWER_W to listOf(DeviceMetric.PowerW, DeviceMetric.ChargePowerW),
        MetricId.BATTERY_PERCENT to listOf(DeviceMetric.BatteryPercent),
        // Two different pieces of hardware, not synonyms. LeaperKim and InMotion have their own
        // MOSFET probe; Begode and KingSong have none at all and report only a general
        // controller temperature. While this was one metric with a fallback, a "MOSFET" alert
        // silently watched a different sensor on half the wheels.
        MetricId.TEMPERATURE_CONTROLLER_C to listOf(
            DeviceMetric.ControllerTemperatureC,
            DeviceMetric.ChargerTemperatureC,
        ),
        MetricId.TEMPERATURE_MOS_C to listOf(DeviceMetric.MosTemperatureC),
        MetricId.TEMPERATURE_MOTOR_C to listOf(DeviceMetric.MotorTemperatureC),
        // One rule: this is the hottest battery reading the device reports, because an alert on
        // battery temperature is an overheat warning and the coldest pack cannot raise one.
        // [DeviceMetric.BatteryTemperatureMinC] is the fallback rather than a second metric:
        // a wheel with a single battery probe fills only the min slot (InMotion V11/V12 map
        // their one `batteryTemperature` there), and without the fallback that reading would be
        // dropped and the alert would look broken on exactly the wheels that do have a sensor.
        // On a wheel that reports both, the max always wins, so the fallback never masks it.
        MetricId.TEMPERATURE_BATTERY_C to listOf(
            DeviceMetric.BatteryTemperatureMaxC,
            DeviceMetric.BatteryTemperatureMinC,
        ),
        MetricId.PITCH_DEG to listOf(DeviceMetric.PitchDeg),
        MetricId.ROLL_DEG to listOf(DeviceMetric.RollDeg),
        MetricId.TRIP_DISTANCE_KM to listOf(DeviceMetric.TripDistanceKm),
        MetricId.TOTAL_DISTANCE_KM to listOf(DeviceMetric.TotalDistanceKm),
    )

    /** Sources for [metricId], most preferred first. */
    fun sources(metricId: MetricId): List<DeviceMetric> = bySources[metricId].orEmpty()

    /** Every alert metric that has at least one source. */
    val mapped: Set<MetricId> get() = bySources.keys

    internal fun project(values: Map<DeviceMetric, Double>): Map<MetricId, Double> =
        buildMap {
            bySources.forEach { (metricId, sources) ->
                sources.firstNotNullOfOrNull { values[it] }?.let { put(metricId, it) }
            }
        }
}
