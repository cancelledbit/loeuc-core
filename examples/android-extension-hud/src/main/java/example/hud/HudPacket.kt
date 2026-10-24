package example.hud

import kotlin.math.roundToLong
import pw.vasilevskiy.loeuc.extension.ExtensionMetric
import pw.vasilevskiy.loeuc.extension.TelemetrySnapshot

/** The readings the HUD shows. `null` is "the wheel does not report it", never zero. */
data class HudValues(
    val speedKmh: Double?,
    val pwmPercent: Double?,
    val batteryPercent: Double?,
    val voltage: Double?,
    val temperatureC: Double?,
    val powerW: Double?,
    val tripKm: Double?,
    val playback: Boolean,
) {
    companion object {
        fun from(snapshot: TelemetrySnapshot) = HudValues(
            speedKmh = snapshot.value(ExtensionMetric.SPEED_KMH),
            pwmPercent = snapshot.value(ExtensionMetric.PWM_PERCENT),
            batteryPercent = snapshot.value(ExtensionMetric.BATTERY_PERCENT),
            voltage = snapshot.value(ExtensionMetric.PACK_VOLTAGE_V),
            temperatureC = snapshot.value(ExtensionMetric.CONTROLLER_TEMPERATURE_C),
            powerW = snapshot.value(ExtensionMetric.POWER_W),
            tripKm = snapshot.value(ExtensionMetric.TRIP_DISTANCE_KM),
            playback = snapshot.isPlayback,
        )
    }
}

/** Something the HUD reported back. */
sealed interface HudEvent {
    data object ShortPress : HudEvent
    data object LongPress : HudEvent
    data class Battery(val percent: Int) : HudEvent
}

/**
 * The wire format between this extension and the HUD. It is yours, not LoEUC's: LoEUC hands the
 * extension telemetry, and what travels to your hardware is whatever your hardware wants.
 *
 * Telemetry, phone to HUD, one 20-byte write - it fits the default ATT payload, so no MTU
 * negotiation is needed. Little-endian:
 *
 * ```
 * 0      magic 0x4C
 * 1      version 1
 * 2      flags: bit 0 - replayed recording, not a live wheel
 * 3      reserved, 0
 * 4..5   speed, km/h x10, i16
 * 6..7   PWM, % x10, i16
 * 8..9   battery, % x10, u16
 * 10..11 pack voltage, V x10, u16
 * 12..13 controller temperature, C x10, i16
 * 14..15 power, W, i16 (clamped)
 * 16..19 trip, metres, u32
 * ```
 *
 * A reading the wheel does not report is sent as the field's "absent" value: 0x7FFF for i16,
 * 0xFFFF for u16, 0xFFFFFFFF for u32. The HUD shows a dash for it rather than a made-up zero.
 *
 * Events, HUD to phone, a 2-byte notification: `01 01` short press, `01 02` long press,
 * `02 <percent>` the HUD's own battery.
 */
object HudPacket {
    const val SIZE = 20
    const val MAGIC = 0x4C
    const val VERSION = 1
    const val FLAG_PLAYBACK = 0x01

    const val ABSENT_I16 = 0x7FFF
    const val ABSENT_U16 = 0xFFFF
    const val ABSENT_U32 = 0xFFFFFFFFL

    fun encode(values: HudValues): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = MAGIC.toByte()
        out[1] = VERSION.toByte()
        out[2] = (if (values.playback) FLAG_PLAYBACK else 0).toByte()
        out.putI16(4, values.speedKmh, 10.0)
        out.putI16(6, values.pwmPercent, 10.0)
        out.putU16(8, values.batteryPercent, 10.0)
        out.putU16(10, values.voltage, 10.0)
        out.putI16(12, values.temperatureC, 10.0)
        out.putI16(14, values.powerW, 1.0)
        out.putU32(16, values.tripKm, 1_000.0)
        return out
    }

    fun decodeEvent(bytes: ByteArray): HudEvent? {
        if (bytes.size < 2) return null
        val value = bytes[1].toInt() and 0xFF
        return when (bytes[0].toInt() and 0xFF) {
            0x01 -> when (value) {
                1 -> HudEvent.ShortPress
                2 -> HudEvent.LongPress
                else -> null
            }
            0x02 -> HudEvent.Battery(value.coerceIn(0, 100))
            else -> null
        }
    }

    private fun scaled(value: Double?, scale: Double): Long? =
        value?.takeIf { it.isFinite() }?.let { (it * scale).roundToLong() }

    // The largest real value stays one below the absent marker, so a clamped reading never
    // reads back as "not reported".
    private fun ByteArray.putI16(offset: Int, value: Double?, scale: Double) {
        val raw = scaled(value, scale)?.coerceIn(-0x8000L, ABSENT_I16 - 1L) ?: ABSENT_I16.toLong()
        putLe(offset, raw, 2)
    }

    private fun ByteArray.putU16(offset: Int, value: Double?, scale: Double) {
        val raw = scaled(value, scale)?.coerceIn(0L, ABSENT_U16 - 1L) ?: ABSENT_U16.toLong()
        putLe(offset, raw, 2)
    }

    private fun ByteArray.putU32(offset: Int, value: Double?, scale: Double) {
        val raw = scaled(value, scale)?.coerceIn(0L, ABSENT_U32 - 1L) ?: ABSENT_U32
        putLe(offset, raw, 4)
    }

    private fun ByteArray.putLe(offset: Int, value: Long, width: Int) {
        for (index in 0 until width) this[offset + index] = (value shr (8 * index)).toByte()
    }
}
