package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.InmotionTelemetry
import kotlin.math.abs

/**
 * InMotion telemetry under brand-neutral names.
 *
 * InMotion is the richest of the wheels: it reports a MOSFET, motor, board and pack probe, tyre
 * pressure and both lean angles.
 */
fun InmotionTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    // Signed on the wire - rolling backwards reads negative. A speed rule asks "faster than",
    // so the sign has to go before the value reaches it.
    DeviceMetric.SpeedKmh to abs(speedKmh),
    DeviceMetric.PwmPercent to pwmPercent,
    DeviceMetric.PackVoltageV to voltage,
    DeviceMetric.BatteryCurrentA to batteryCurrent,
    DeviceMetric.PhaseCurrentA to phaseCurrent,
    DeviceMetric.PowerW to power,
    DeviceMetric.MotorPowerW to motorPower,
    DeviceMetric.ApparentPowerVa to voltage * phaseCurrent * pwmPercent / 100.0,
    DeviceMetric.BatteryPercent to batteryPercent,
    DeviceMetric.ControllerTemperatureC to temperature,
    DeviceMetric.MosTemperatureC to mosTemperature,
    DeviceMetric.MotorTemperatureC to motorTemperature,
    DeviceMetric.BoardTemperatureC to boardTemperature,
    DeviceMetric.BatteryTemperatureMinC to batteryTemperature,
    DeviceMetric.BatteryTemperatureMaxC to batteryMaxCellTemperature,
    DeviceMetric.PitchDeg to pitchAngle,
    DeviceMetric.RollDeg to lateralAngle,
    DeviceMetric.TirePressureBar to tirePressureBar,
    DeviceMetric.DangerSpeedKmh to speedTiltBackKmh,
    DeviceMetric.TripDistanceKm to tripDistanceMeters / MetresPerKilometre,
    DeviceMetric.TotalDistanceKm to totalDistanceMeters / MetresPerKilometre,
    // The V13/V14/P6 realtime frame carries the charge rail too, so an InMotion on the charger
    // fills the same metrics a standalone charger would.
    DeviceMetric.ChargeVoltageV to chargeVoltage,
    DeviceMetric.ChargeCurrentA to chargeCurrent,
    DeviceMetric.Charging to charging,
)
