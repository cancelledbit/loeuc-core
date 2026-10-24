package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.math.roundToLong

/**
 * Dragy GNSS performance meters (Dragy Pro, `DRG70` / `DRG71`) over Bluetooth LE.
 *
 * A Dragy is a u-blox receiver behind a BLE serial bridge. Everything that travels through the
 * bridge is plain UBX: the receiver's own output arrives on [STREAM], and whatever is written to
 * [UART_TX] goes straight into the receiver. There is no Dragy framing around it.
 *
 * Provenance - two independent sources that agree on the stream, the status channel and the
 * write channel:
 *  - a third-party Android app that ships Dragy support ("verified on DRG71"); its native core
 *    subscribes to `FD02`, `FD04`, reads `2A24`, writes the rate, time and position messages
 *    below to `FD01`, and never touches `FD03`;
 *  - the Apache-2.0 notes of `jremick/dragy-dash` (hands-on with a DRG70), which list the same
 *    characteristics and add the `FD03` challenge and the `FD04` battery byte.
 *
 * The one disagreement is [AUTH]: the notes say the stream does not start without the challenge
 * response, the app streams without it. A client that answers the challenge when the
 * characteristic exists covers both readings - the response only proves the client knows the
 * scheme and changes no receiver setting.
 */
object DragyGatt {
    const val SERVICE = "0000fd00-0000-1000-8000-00805f9b34fb"

    /** Write: bytes go into the receiver's UART ("spp data tx"). */
    const val UART_TX = "0000fd01-0000-1000-8000-00805f9b34fb"

    /** Notify: the receiver's UBX output, fragmented ("spp data rx"). */
    const val STREAM = "0000fd02-0000-1000-8000-00805f9b34fb"

    /** Read + write: two challenge bytes, answered by [dragyAuthResponse]. */
    const val AUTH = "0000fd03-0000-1000-8000-00805f9b34fb"

    /** Read + notify: device status ("device control"); byte 1 is the battery charge. */
    const val STATUS = "0000fd04-0000-1000-8000-00805f9b34fb"

    /** Device Information: model number string, e.g. `DRG71`. */
    const val MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"

    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * Whether a BLE advertisement looks like a Dragy. The service alone is not enough - other
     * vendors use `FD00` too - so the name has to agree when there is one.
     */
    fun looksLikeDragy(name: String?, advertisesService: Boolean): Boolean {
        val trimmed = name?.trim().orEmpty()
        val namedDragy = trimmed.startsWith("DRG", ignoreCase = true) ||
            trimmed.contains("dragy", ignoreCase = true)
        return namedDragy || (advertisesService && trimmed.isEmpty())
    }
}

/**
 * The answer to the `FD03` challenge: `[a, b, a xor b, a and b]` over its first two bytes.
 * Returns `null` for a challenge shorter than two bytes.
 */
@ExperimentalWriteApi
fun dragyAuthResponse(challenge: ByteArray): ByteArray? {
    if (challenge.size < 2) return null
    val a = challenge[0].toInt() and 0xFF
    val b = challenge[1].toInt() and 0xFF
    return byteArrayOf(a.toByte(), b.toByte(), (a xor b).toByte(), (a and b).toByte())
}

/** Battery charge from an `FD04` status packet, when byte 1 holds a plausible percentage. */
fun dragyBatteryPercent(status: ByteArray): Int? {
    if (status.size < 2) return null
    val value = status[1].toInt() and 0xFF
    return value.takeIf { it in 0..100 }
}

/**
 * Update rates the model accepts. Only the Pro (`DRG71`) is known to reach 25 Hz; anything else
 * stays at the 10 Hz every Dragy resets to when a phone connects.
 */
fun dragySupportedRates(modelNumber: String?): List<Int> =
    if (modelNumber?.contains("DRG71", ignoreCase = true) == true) listOf(10, 20, 25) else listOf(10)

/** One UBX message: class, id and payload, with sync bytes and checksum already stripped. */
class UbxMessage(val messageClass: Int, val messageId: Int, val payload: ByteArray) {
    val key: Int get() = (messageClass shl 8) or messageId
}

/**
 * Splits a UBX byte stream into whole, checksum-verified messages.
 *
 * BLE delivers the receiver's output in fragments that ignore message boundaries, and a NAV-PVT
 * (100 bytes on the wire) never fits one notification. Bytes that do not start a valid message
 * are skipped one at a time, so a corrupted frame costs that frame and nothing after it.
 */
class UbxStreamReassembler(private val maxPayload: Int = 512) {

    private var buffer = ByteArray(0)

    /** Number of frames dropped on a bad checksum or an implausible length since creation. */
    var rejectedFrames: Int = 0
        private set

    fun reset() {
        buffer = ByteArray(0)
    }

    fun consume(chunk: ByteArray): List<UbxMessage> {
        buffer += chunk
        val messages = ArrayList<UbxMessage>()
        var start = 0
        while (true) {
            val sync = buffer.indexOfUbxSync(start)
            if (sync < 0) {
                // Keep a trailing 0xB5: it may be the first half of a sync split across chunks.
                start = if (buffer.isNotEmpty() && buffer.last() == UBX_SYNC_1) buffer.size - 1 else buffer.size
                break
            }
            start = sync
            if (buffer.size - start < 6) break
            val length = u16(buffer, start + 4)
            if (length > maxPayload) {
                rejectedFrames++
                start += 1
                continue
            }
            val total = 6 + length + 2
            if (buffer.size - start < total) break
            val (ckA, ckB) = ubxChecksum(buffer, start + 2, 4 + length)
            if (buffer[start + total - 2] != ckA || buffer[start + total - 1] != ckB) {
                rejectedFrames++
                start += 1
                continue
            }
            messages += UbxMessage(
                messageClass = buffer[start + 2].toInt() and 0xFF,
                messageId = buffer[start + 3].toInt() and 0xFF,
                payload = buffer.copyOfRange(start + 6, start + 6 + length),
            )
            start += total
        }
        buffer = buffer.copyOfRange(start, buffer.size)
        return messages
    }
}

/** Fix type as UBX NAV-PVT reports it. */
enum class GnssFixType(val code: Int) {
    NoFix(0), DeadReckoning(1), Fix2D(2), Fix3D(3), GnssDeadReckoning(4), TimeOnly(5);

    companion object {
        fun of(code: Int): GnssFixType = entries.firstOrNull { it.code == code } ?: NoFix
    }
}

/**
 * One UBX NAV-PVT solution, in SI units.
 *
 * [utcEpochMillis] is `null` until the receiver marks both date and time valid. [isUsable] is
 * the gate for anything that draws a track or times a run: a 2D/3D fix the receiver itself
 * flags as valid (`gnssFixOK`).
 */
data class GnssFix(
    val iTowMillis: Long,
    val utcEpochMillis: Long?,
    val fixType: GnssFixType,
    val gnssFixOk: Boolean,
    val satellitesUsed: Int,
    val latitude: Double,
    val longitude: Double,
    val heightEllipsoidMeters: Double,
    val altitudeMslMeters: Double,
    val horizontalAccuracyMeters: Double,
    val verticalAccuracyMeters: Double,
    val velocityNorthMps: Double,
    val velocityEastMps: Double,
    val velocityDownMps: Double,
    val groundSpeedMps: Double,
    val headingDegrees: Double,
    val speedAccuracyMps: Double,
    val headingAccuracyDegrees: Double,
    val pDop: Double,
) {
    val isUsable: Boolean
        get() = gnssFixOk && (fixType == GnssFixType.Fix2D || fixType == GnssFixType.Fix3D ||
            fixType == GnssFixType.GnssDeadReckoning)

    val groundSpeedKmh: Double get() = groundSpeedMps * 3.6
}

/** Satellites the receiver hears and uses, from UBX NAV-SAT. */
data class GnssSatelliteSummary(
    val heard: Int,
    val used: Int,
    /** Median carrier-to-noise of the used satellites, dB-Hz; `null` when none is used. */
    val medianUsedCn0: Int?,
    val maxCn0: Int?,
)

/** Interference indicators from UBX MON-RF (first RF block) and NAV-STATUS. */
data class GnssInterference(
    /** 0 unknown, 1 ok, 2 warning, 3 critical. */
    val jammingState: Int?,
    /** 0..255, CW jamming indicator. */
    val jamIndicator: Int?,
    /** 0 unknown, 1 no spoofing, 2 spoofing indicated, 3 multiple indications. */
    val spoofState: Int?,
)

object UbxDecoder {
    const val NAV_PVT = 0x0107
    const val NAV_STATUS = 0x0103
    const val NAV_SAT = 0x0135
    const val MON_RF = 0x0A38

    fun navPvt(message: UbxMessage): GnssFix? {
        if (message.key != NAV_PVT || message.payload.size < 92) return null
        val p = message.payload
        val year = u16(p, 4)
        val valid = p[11].toInt() and 0xFF
        val validDate = valid and 0x01 != 0
        val validTime = valid and 0x02 != 0
        val utc = if (validDate && validTime && year >= 2000) {
            val nanos = i32(p, 16)
            epochMillisUtc(
                year = year,
                month = p[6].toInt() and 0xFF,
                day = p[7].toInt() and 0xFF,
                hour = p[8].toInt() and 0xFF,
                minute = p[9].toInt() and 0xFF,
                second = p[10].toInt() and 0xFF,
            ) + floorDiv(nanos.toLong(), 1_000_000L)
        } else {
            null
        }
        val flags = p[21].toInt() and 0xFF
        return GnssFix(
            iTowMillis = u32(p, 0),
            utcEpochMillis = utc,
            fixType = GnssFixType.of(p[20].toInt() and 0xFF),
            gnssFixOk = flags and 0x01 != 0,
            satellitesUsed = p[23].toInt() and 0xFF,
            longitude = i32(p, 24) * 1e-7,
            latitude = i32(p, 28) * 1e-7,
            heightEllipsoidMeters = i32(p, 32) / 1000.0,
            altitudeMslMeters = i32(p, 36) / 1000.0,
            horizontalAccuracyMeters = u32(p, 40) / 1000.0,
            verticalAccuracyMeters = u32(p, 44) / 1000.0,
            velocityNorthMps = i32(p, 48) / 1000.0,
            velocityEastMps = i32(p, 52) / 1000.0,
            velocityDownMps = i32(p, 56) / 1000.0,
            groundSpeedMps = i32(p, 60) / 1000.0,
            headingDegrees = i32(p, 64) * 1e-5,
            speedAccuracyMps = u32(p, 68) / 1000.0,
            headingAccuracyDegrees = u32(p, 72) * 1e-5,
            pDop = u16(p, 76) * 0.01,
        )
    }

    fun navSat(message: UbxMessage): GnssSatelliteSummary? {
        if (message.key != NAV_SAT || message.payload.size < 8) return null
        val p = message.payload
        val count = p[5].toInt() and 0xFF
        if (p.size < 8 + count * 12) return null
        var heard = 0
        val usedCn0 = ArrayList<Int>()
        var maxCn0: Int? = null
        for (index in 0 until count) {
            val base = 8 + index * 12
            val cn0 = p[base + 2].toInt() and 0xFF
            val flags = u32(p, base + 8)
            if (cn0 > 0) {
                heard++
                maxCn0 = maxOf(maxCn0 ?: 0, cn0)
            }
            if (flags and 0x08L != 0L) usedCn0 += cn0
        }
        usedCn0.sort()
        val median = if (usedCn0.isEmpty()) null else usedCn0[usedCn0.size / 2]
        return GnssSatelliteSummary(heard = heard, used = usedCn0.size, medianUsedCn0 = median, maxCn0 = maxCn0)
    }

    /** Spoofing detection state from NAV-STATUS `flags2` bits 3..4. */
    fun navStatusSpoofState(message: UbxMessage): Int? {
        if (message.key != NAV_STATUS || message.payload.size < 16) return null
        return (message.payload[7].toInt() shr 3) and 0x03
    }

    /** Jamming state and indicator of the first RF block of MON-RF. */
    fun monRf(message: UbxMessage): Pair<Int, Int>? {
        if (message.key != MON_RF || message.payload.size < 4 + 24) return null
        val p = message.payload
        if ((p[1].toInt() and 0xFF) < 1) return null
        val block = 4
        return (p[block + 1].toInt() and 0x03) to (p[block + 16].toInt() and 0xFF)
    }
}

/** Builders for the UBX messages a Dragy client sends into the receiver through [DragyGatt.UART_TX]. */
object UbxCommands {

    /** Wraps [payload] into a UBX frame with sync bytes, length and Fletcher checksum. */
    fun frame(messageClass: Int, messageId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = UBX_SYNC_1
        out[1] = UBX_SYNC_2
        out[2] = messageClass.toByte()
        out[3] = messageId.toByte()
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 6)
        val (a, b) = ubxChecksum(out, 2, 4 + payload.size)
        out[out.size - 2] = a
        out[out.size - 1] = b
        return out
    }

    /** Asks the receiver for one MON-RF report. A poll only; changes nothing. */
    fun pollMonRf(): ByteArray = frame(0x0A, 0x38)

    /** Asks the receiver for one NAV-STATUS report. A poll only; changes nothing. */
    fun pollNavStatus(): ByteArray = frame(0x01, 0x03)

    /**
     * CFG-VALSET of `CFG-RATE-MEAS` (key `0x30210001`) into the RAM and battery-backed layers:
     * the measurement period that sets how often NAV-PVT arrives. Returns `null` for a rate
     * outside 10/20/25 Hz - the only ones the reference client ever sends.
     */
    @ExperimentalWriteApi
    fun setMeasurementRate(hz: Int): ByteArray? {
        val periodMs = when (hz) {
            10 -> 100
            20 -> 50
            25 -> 40
            else -> return null
        }
        val payload = byteArrayOf(
            0x00, 0x03, 0x00, 0x00, // version 0, layers RAM|BBR, reserved
            0x01, 0x00, 0x21, 0x30, // key CFG-RATE-MEAS, little-endian
            (periodMs and 0xFF).toByte(), ((periodMs shr 8) and 0xFF).toByte(),
        )
        return frame(0x06, 0x8A, payload)
    }

    /**
     * MGA-INI-TIME_UTC: tells a cold receiver the current time to within two seconds so it
     * knows which satellites to look for. Assistance only; it is forgotten on power-off.
     */
    @ExperimentalWriteApi
    fun initialTime(epochMillis: Long): ByteArray {
        val c = civilFromEpochMillis(epochMillis)
        val nanos = (floorMod(epochMillis, 1000L) * 1_000_000L).toInt()
        val p = ByteArray(24)
        p[0] = 0x10 // type: TIME_UTC
        p[1] = 0x00 // version
        p[2] = 0x00 // ref: none, time is valid on receipt
        p[3] = 0x80.toByte() // leap seconds unknown
        putU16(p, 4, c.year)
        p[6] = c.month.toByte()
        p[7] = c.day.toByte()
        p[8] = c.hour.toByte()
        p[9] = c.minute.toByte()
        p[10] = c.second.toByte()
        putU32(p, 12, nanos.toLong())
        putU16(p, 16, 2) // tAccS
        putU32(p, 20, 0)
        return frame(0x13, 0x40, p)
    }

    /** MGA-INI-POS_LLH: approximate position for a cold start. Assistance only. */
    @ExperimentalWriteApi
    fun initialPosition(latitude: Double, longitude: Double, altitudeMeters: Double, accuracyMeters: Double): ByteArray {
        val p = ByteArray(20)
        p[0] = 0x01 // type: POS_LLH
        p[1] = 0x00
        putU32(p, 4, (latitude * 1e7).roundToLong())
        putU32(p, 8, (longitude * 1e7).roundToLong())
        putU32(p, 12, (altitudeMeters * 100.0).roundToLong())
        putU32(p, 16, (accuracyMeters.coerceIn(1.0, 10_000.0) * 100.0).roundToLong())
        return frame(0x13, 0x40, p)
    }
}

private const val UBX_SYNC_1: Byte = 0xB5.toByte()
private const val UBX_SYNC_2: Byte = 0x62

private fun ByteArray.indexOfUbxSync(from: Int): Int {
    for (index in from until size - 1) {
        if (this[index] == UBX_SYNC_1 && this[index + 1] == UBX_SYNC_2) return index
    }
    return -1
}

internal fun ubxChecksum(bytes: ByteArray, offset: Int, length: Int): Pair<Byte, Byte> {
    var a = 0
    var b = 0
    for (index in offset until offset + length) {
        a = (a + (bytes[index].toInt() and 0xFF)) and 0xFF
        b = (b + a) and 0xFF
    }
    return a.toByte() to b.toByte()
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, offset: Int): Long =
    (u16(bytes, offset).toLong()) or (u16(bytes, offset + 2).toLong() shl 16)

private fun i32(bytes: ByteArray, offset: Int): Int = u32(bytes, offset).toInt()

private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 4) bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
}

private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = floorDiv(y, 400)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun epochMillisUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    ((daysFromCivil(year, month, day) * 24 + hour) * 60 + minute) * 60_000L + second * 1000L

private class Civil(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)

private fun civilFromEpochMillis(epochMillis: Long): Civil {
    val days = floorDiv(epochMillis, 86_400_000L)
    val msOfDay = floorMod(epochMillis, 86_400_000L)
    val z = days + 719468
    val era = floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    val secondsOfDay = (msOfDay / 1000).toInt()
    return Civil(year, month, day, secondsOfDay / 3600, (secondsOfDay / 60) % 60, secondsOfDay % 60)
}
