package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingCapability
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

/**
 * A KingSong settings row under the neutral shape. A row is writable when it has a command, even
 * before the wheel has reported its value - except that the speed-alarm write is refused until
 * all four speeds are known, which [WheelSession.writeSetting] reports as a rejection.
 */
fun KingSongSettingCapability.toDeviceSettingCapability(): DeviceSettingCapability =
    DeviceSettingCapability(key, rawValue.takeIf { isSupported }, writable = isSupported && commandId != null)
