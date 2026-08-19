package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.KingSongTelemetry

/**
 * KingSong telemetry under brand-neutral names.
 *
 * KingSong reports no charge level, no MOSFET or motor probe and no lean angle, so those
 * metrics are absent. [KingSongTelemetry.secondaryTemperature] is deliberately not mapped: it
 * is the second, slower probe the engine keeps apart from the live one.
 */
fun KingSongTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    DeviceMetric.SpeedKmh to speedKmh,
    DeviceMetric.PwmPercent to pwmPercent,
    DeviceMetric.PackVoltageV to voltage,
    DeviceMetric.BatteryCurrentA to current,
    DeviceMetric.PhaseCurrentA to phaseCurrent,
    DeviceMetric.PowerW to power,
    DeviceMetric.ControllerTemperatureC to temperature,
    DeviceMetric.TripDistanceKm to tripDistanceMeters / MetresPerKilometre,
    DeviceMetric.TotalDistanceKm to totalDistanceKm,
)
