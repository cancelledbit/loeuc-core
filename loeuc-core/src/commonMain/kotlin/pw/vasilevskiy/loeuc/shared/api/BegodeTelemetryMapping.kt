package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.BegodeTelemetry

/** Metres per kilometre, for brands that report distance in metres. */
internal const val MetresPerKilometre: Double = 1_000.0

/**
 * Begode telemetry under brand-neutral names.
 *
 * Fields Begode never reports - MOSFET probe, lean and pitch angles, output current - are left
 * out rather than zeroed: a metric that reads zero looks like a working sensor to an alert.
 */
fun BegodeTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    DeviceMetric.SpeedKmh to speedKmh,
    DeviceMetric.PwmPercent to pwmPercent,
    DeviceMetric.PackVoltageV to voltage,
    DeviceMetric.BatteryCurrentA to current,
    DeviceMetric.PhaseCurrentA to phaseCurrent,
    DeviceMetric.PowerW to power,
    DeviceMetric.BatteryPercent to batteryPercent,
    DeviceMetric.ControllerTemperatureC to temperature,
    DeviceMetric.MotorTemperatureC to motorTemperature,
    DeviceMetric.TripDistanceKm to tripDistanceMeters / MetresPerKilometre,
    DeviceMetric.TotalDistanceKm to totalDistanceKm,
)
