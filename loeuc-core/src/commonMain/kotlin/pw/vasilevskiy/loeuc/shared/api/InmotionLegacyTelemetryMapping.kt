package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyIdentity
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettings
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyTelemetry

/**
 * Legacy InMotion (V5 / V8 / V10 family) telemetry under brand-neutral names.
 *
 * The wheel reports one current. Power on the wire is that current times pack voltage, so it is
 * the current in and out of the pack and lands in [DeviceMetric.BatteryCurrentA]. Fields the
 * engine leaves null - roll on the modern generation, an implausible second probe, a battery
 * percentage the model has no curve for - stay absent rather than reading as zero.
 */
fun InmotionLegacyTelemetry.toDeviceTelemetry(): DeviceTelemetry = DeviceTelemetry.of(
    // Already unsigned: the engine takes the absolute value before scaling.
    DeviceMetric.SpeedKmh to speedKmh,
    DeviceMetric.PackVoltageV to voltage,
    DeviceMetric.BatteryCurrentA to current,
    DeviceMetric.PowerW to power,
    DeviceMetric.BatteryPercent to (batteryPercent?.toDouble() ?: Double.NaN),
    DeviceMetric.ControllerTemperatureC to temperature.toDouble(),
    // The official app labels the second probe MOS.
    DeviceMetric.MosTemperatureC to (secondTemperature?.toDouble() ?: Double.NaN),
    DeviceMetric.PitchDeg to pitchDegrees,
    DeviceMetric.RollDeg to (rollDegrees ?: Double.NaN),
    DeviceMetric.TripDistanceKm to tripDistanceMeters / MetresPerKilometre,
    DeviceMetric.TotalDistanceKm to totalDistanceMeters / MetresPerKilometre,
)

/**
 * Setting keys of the legacy family, identical to the ones the LoEUC apps persist and group.
 * [InmotionLegacySettingsCatalog] builds the rows and the writes behind them.
 */
object InmotionLegacySettingKeys {
    const val MaxSpeed: String = "inmotion_legacy_max_speed"
    const val PedalHardness: String = "inmotion_legacy_pedal_hardness"
    const val PedalTilt: String = "inmotion_legacy_pedal_tilt"
    const val RideMode: String = "inmotion_legacy_ride_mode"
    const val Headlight: String = "inmotion_legacy_headlight"
    const val DecorativeLight: String = "inmotion_legacy_decorative_light"
    const val SpeakerVolume: String = "inmotion_legacy_speaker_volume"
    const val HandleButton: String = "inmotion_legacy_handle_button"

    val all: List<String> = listOf(
        MaxSpeed, PedalHardness, PedalTilt, RideMode, Headlight, DecorativeLight, SpeakerVolume, HandleButton,
    )
}

/**
 * The settings a slow info reply carried. A setting is writable when
 * [InmotionLegacySettingsCatalog] would build its write for the wheel [identity] names: the model
 * is known, has the setting, and reports the field back. A field the payload is too short to hold -
 * most of them on a V5F - is left out rather than reported as zero.
 *
 * Raw values: pedal tilt in tenths of a degree; ride mode 1 for classic, 0 for comfort; the
 * handle button 1 when enabled.
 */
fun InmotionLegacySettings.toDeviceSettingCapabilities(identity: InmotionLegacyIdentity?): List<DeviceSettingCapability> =
    InmotionLegacySettingsCatalog.capabilities(identity, this).map { row ->
        DeviceSettingCapability(row.key, row.rawValue, writable = row.commandId != null)
    }

/** The same rows with no identity to write for, so every one is read-only. */
fun InmotionLegacySettings.toDeviceSettingCapabilities(): List<DeviceSettingCapability> =
    toDeviceSettingCapabilities(identity = null)
