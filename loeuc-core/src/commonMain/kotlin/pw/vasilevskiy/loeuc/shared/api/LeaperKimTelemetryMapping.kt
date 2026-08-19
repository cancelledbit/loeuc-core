package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimTelemetry

private const val LeaperKimMetresPerKilometre = 1_000.0

/** Maps the established LeaperKim live fields without conflating settings and readings. */
fun LeaperKimTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    DeviceMetric.SpeedKmh to speedKmh,
    DeviceMetric.PwmPercent to pwmPercent,
    DeviceMetric.PackVoltageV to voltage,
    DeviceMetric.BatteryCurrentA to batteryCurrent,
    DeviceMetric.OutputCurrentA to outputCurrent,
    DeviceMetric.PhaseCurrentA to phaseCurrent,
    DeviceMetric.PowerW to power,
    DeviceMetric.ControllerTemperatureC to temperature,
    DeviceMetric.MosTemperatureC to mosTemperature,
    DeviceMetric.MotorTemperatureC to motorTemperature,
    DeviceMetric.BatteryPercent to batteryPercent,
    DeviceMetric.BatteryTemperatureMinC to batteryTemperatureMin,
    DeviceMetric.BatteryTemperatureMaxC to batteryTemperatureMax,
    DeviceMetric.PitchDeg to carPose,
    DeviceMetric.RollDeg to lateralAngle,
    DeviceMetric.DangerSpeedKmh to dangerSpeedKmh,
    DeviceMetric.FallProtectionAngleDeg to fallProtectionAngle,
    DeviceMetric.TripDistanceKm to tripDistanceMeters / LeaperKimMetresPerKilometre,
    DeviceMetric.TotalDistanceKm to totalDistanceMeters / LeaperKimMetresPerKilometre,
)
