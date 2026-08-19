package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.HwChargerTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.SkatChargerTelemetry

/**
 * HW smart charger telemetry under brand-neutral names.
 *
 * Every wheel-side metric stays absent: a charger has neither a pack nor a controller, and
 * everything it measures belongs to the charge metrics. `input*` is the mains side and is left
 * on the brand object - a rider reads the battery side.
 */
fun HwChargerTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    DeviceMetric.ChargeVoltageV to outputVoltage,
    DeviceMetric.ChargeCurrentA to outputCurrent,
    DeviceMetric.ChargePowerW to outputPower,
    DeviceMetric.ChargerTemperatureC to temperature,
    DeviceMetric.ChargedAmpHours to ah,
    DeviceMetric.ChargedWattHours to wh,
    // -1 is the "no frame yet" default, and it has to stay unknown rather than become 0.
    DeviceMetric.Charging to when {
        outputState < 0 -> Double.NaN
        outputState == 1 -> 1.0
        else -> 0.0
    },
)

/**
 * SKAT / CAN-Control charger telemetry under brand-neutral names.
 *
 * There is no output switch to read, so charging is inferred from current actually flowing.
 * Only the FB frame carries temperature, so a run of FA-only frames leaves it unknown.
 */
fun SkatChargerTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    DeviceMetric.ChargeVoltageV to outputVoltage,
    DeviceMetric.ChargeCurrentA to outputCurrent,
    DeviceMetric.ChargePowerW to power,
    DeviceMetric.ChargerTemperatureC to temperature,
    DeviceMetric.ChargeTargetVoltageV to targetVoltage,
    DeviceMetric.ChargedAmpHours to ampHours,
    DeviceMetric.ChargedWattHours to wattHours,
    DeviceMetric.Charging to when {
        !outputCurrent.isFinite() -> Double.NaN
        outputCurrent > SkatChargingCurrentThresholdA -> 1.0
        else -> 0.0
    },
)

/** Below this the reading is noise around zero rather than energy going into the pack. */
private const val SkatChargingCurrentThresholdA: Double = 0.1
