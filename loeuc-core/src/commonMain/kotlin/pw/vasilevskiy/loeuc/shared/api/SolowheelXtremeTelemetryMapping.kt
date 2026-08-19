package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.SolowheelXtremeTelemetry

/**
 * Solowheel Xtreme telemetry under brand-neutral names.
 *
 * The ASCII stream carries speed and pack voltage only. `motionRaw` and `stateRaw` stay on the
 * brand object: they are state flags, not measurements.
 */
fun SolowheelXtremeTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    DeviceMetric.SpeedKmh to speedKmh,
    DeviceMetric.PackVoltageV to voltage,
)
