package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.NinebotProtocolUpdate

private const val NinebotMetresPerKilometre = 1_000.0

/** Maps one Ninebot update; the engine may emit several updates for one input chunk. */
fun NinebotProtocolUpdate.toDeviceTelemetry(): DeviceTelemetry {
    val live = telemetry
    val liveTelemetry = if (live == null) {
        DeviceTelemetry.of()
    } else {
        DeviceTelemetry.of(
            DeviceMetric.SpeedKmh to live.speedKmh,
            DeviceMetric.PackVoltageV to live.voltage,
            DeviceMetric.BatteryCurrentA to live.current,
            DeviceMetric.PowerW to live.power,
            DeviceMetric.PwmPercent to live.pwmPercent,
            DeviceMetric.ControllerTemperatureC to live.temperature,
            DeviceMetric.BatteryPercent to live.batteryPercent,
            DeviceMetric.TripDistanceKm to live.tripDistanceMeters / NinebotMetresPerKilometre,
            DeviceMetric.TotalDistanceKm to live.totalDistanceMeters / NinebotMetresPerKilometre,
        )
    }
    val cells = bmsPacks.orEmpty().flatMap { it.cells }.filter(Double::isFinite)
    if (cells.isEmpty()) return liveTelemetry
    val average = cells.average()
    val delta = cells.maxOrNull()!! - cells.minOrNull()!!
    return liveTelemetry.mergedWith(
        DeviceTelemetry.of(
            DeviceMetric.CellVoltageAvgV to average,
            DeviceMetric.CellVoltageDeltaV to delta,
        ),
    )
}
