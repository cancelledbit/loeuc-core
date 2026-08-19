package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/**
 * SKAT / "CAN-Control" charger (advertised name `CAN_CKAT <range>`, vendor UI a WeChat mini
 * program `com.tencent.weauth`, rectifier module `ZTE4875`). Reverse-engineered entirely from a
 * real capture, `loeuc_840D8E16B9EA_unknown_20260814_152810.jsonl`, cross-checked against a
 * screenshot of the vendor app - the mini program's protocol code is not in the host APK, so the
 * dump is the only source of truth.
 *
 * Unlike the `hwcdq` charger this shares nothing with: notify is on FFF1, the phone writes
 * nothing at all (the charger streams unprompted, no poll, no password), and the payload is plain
 * little-endian float32 with a one-byte frame marker and no checksum.
 *
 * Two frame types, distinguished by the marker byte:
 *  - `0xFA`, 25 bytes: live telemetry, ~2/s. Marker + 6 float32.
 *  - `0xFB`, 99 bytes: extended status, less often. A 31-byte header of int32 counters, then the
 *    same six telemetry floats plus temperature and further fields.
 *
 * With no checksum, frames are validated structurally: the third float is the product of the
 * first two (output power = V * I), which held on all 844 telemetry frames of the capture and
 * rejects a marker byte that merely happened to fall inside another frame's float data.
 */
data class SkatChargerTelemetry(
    val outputVoltage: Double = Double.NaN,
    val outputCurrent: Double = Double.NaN,
    val power: Double = Double.NaN,
    val targetVoltage: Double = Double.NaN,
    val ampHours: Double = Double.NaN,
    val wattHours: Double = Double.NaN,
    // Only the FB frame carries temperature; a run of FA-only frames leaves it unknown.
    val temperature: Double = Double.NaN,
    val rawFrame: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkatChargerTelemetry) return false
        if (outputVoltage != other.outputVoltage) return false
        if (outputCurrent != other.outputCurrent) return false
        if (power != other.power) return false
        if (targetVoltage != other.targetVoltage) return false
        if (ampHours != other.ampHours) return false
        if (wattHours != other.wattHours) return false
        if (temperature != other.temperature) return false
        if (rawFrame != null) {
            if (other.rawFrame == null) return false
            if (!rawFrame.contentEquals(other.rawFrame)) return false
        } else if (other.rawFrame != null) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = outputVoltage.hashCode()
        result = 31 * result + outputCurrent.hashCode()
        result = 31 * result + power.hashCode()
        result = 31 * result + targetVoltage.hashCode()
        result = 31 * result + ampHours.hashCode()
        result = 31 * result + wattHours.hashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + (rawFrame?.contentHashCode() ?: 0)
        return result
    }
}

data class SkatChargerFrame(
    val marker: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkatChargerFrame) return false
        if (marker != other.marker) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = marker
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class SkatChargerReassemblyResult(
    val frames: List<SkatChargerFrame>,
    val remainingBuffer: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkatChargerReassemblyResult) return false
        if (frames != other.frames) return false
        if (!remainingBuffer.contentEquals(other.remainingBuffer)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = frames.hashCode()
        result = 31 * result + remainingBuffer.contentHashCode()
        return result
    }
}

class SkatChargerProtocolEngine {
    private var buffer = byteArrayOf()
    private var state = SkatChargerTelemetry()

    fun reset() {
        buffer = byteArrayOf()
        state = SkatChargerTelemetry()
    }

    /**
     * The charger streams on its own; there is nothing to poll. Kept as an explicit empty list so
     * the transport never invents a write to a device that reads none.
     */
    fun pollingCommands(): List<ByteArray> = emptyList()

    fun consume(chunk: ByteArray): SkatChargerTelemetry? {
        buffer += chunk
        val result = reassembleSkatChargerFrames(buffer)
        buffer = result.remainingBuffer

        var updated = false
        for (frame in result.frames) {
            if (applyFrame(frame.bytes)) {
                updated = true
            }
        }
        return if (updated) state else null
    }

    fun consumeFrame(frame: ByteArray): SkatChargerTelemetry? {
        return if (applyFrame(frame)) state else null
    }

    private fun applyFrame(frame: ByteArray): Boolean {
        val decoded = decodeSkatChargerFrame(frame) ?: return false
        // The FB frame carries temperature; the FA frame does not, so a bare FA update must not
        // wipe the last known temperature back to unknown.
        state = decoded.copy(
            temperature = if (decoded.temperature.isNaN()) state.temperature else decoded.temperature,
        )
        return true
    }
}

/**
 * Decodes one whole SKAT frame, or null if it is neither a valid `FA` telemetry frame nor a valid
 * `FB` status frame. Validation is the `power == V * I` invariant plus a finiteness check, which
 * is what stands in for the missing checksum.
 */
fun decodeSkatChargerFrame(frame: ByteArray): SkatChargerTelemetry? {
    return when {
        // FA: marker + V, I, P, target, Ah, Wh - six contiguous floats, no temperature.
        frame.size == SKAT_TELEMETRY_FRAME_SIZE &&
            (frame[0].toInt() and 0xFF) == SKAT_TELEMETRY_MARKER ->
            decodeTelemetry(
                frame,
                voltageOffset = 1,
                targetVoltageOffset = 13,
                ampHoursOffset = 17,
                wattHoursOffset = 21,
                temperatureOffset = -1,
            )

        // FB: after the 31-byte header, the same fields but with a temperature float inserted
        // between power and target voltage, so target/Ah/Wh sit one float further along than in FA.
        frame.size == SKAT_STATUS_FRAME_SIZE &&
            (frame[0].toInt() and 0xFF) == SKAT_STATUS_MARKER ->
            decodeTelemetry(
                frame,
                voltageOffset = SKAT_STATUS_TELEMETRY_OFFSET,
                targetVoltageOffset = SKAT_STATUS_TELEMETRY_OFFSET + 16,
                ampHoursOffset = SKAT_STATUS_TELEMETRY_OFFSET + 20,
                wattHoursOffset = SKAT_STATUS_TELEMETRY_OFFSET + 24,
                temperatureOffset = SKAT_STATUS_TEMPERATURE_OFFSET,
            )

        else -> null
    }
}

private fun decodeTelemetry(
    frame: ByteArray,
    voltageOffset: Int,
    targetVoltageOffset: Int,
    ampHoursOffset: Int,
    wattHoursOffset: Int,
    temperatureOffset: Int,
): SkatChargerTelemetry? {
    val voltage = frame.floatLeAt(voltageOffset)
    val current = frame.floatLeAt(voltageOffset + 4)
    val power = frame.floatLeAt(voltageOffset + 8)
    if (!voltage.isFinite() || !current.isFinite() || !power.isFinite()) return null
    if (!isConsistentPower(voltage, current, power)) return null
    // Defense in depth: a fragment that survived the power check but was stitched across a frame
    // boundary still reads garbage voltage/current. Reject values no charger could produce so a
    // desynced stream shows nothing rather than 1e24 volts.
    if (voltage < 0f || voltage > SKAT_MAX_PLAUSIBLE_VOLTAGE) return null
    if (kotlin.math.abs(current) > SKAT_MAX_PLAUSIBLE_CURRENT) return null

    val temperature = if (temperatureOffset >= 0) frame.floatLeAt(temperatureOffset) else Float.NaN
    val plausibleTemperature = temperature.isFinite() &&
        temperature >= SKAT_MIN_PLAUSIBLE_TEMPERATURE && temperature <= SKAT_MAX_PLAUSIBLE_TEMPERATURE
    return SkatChargerTelemetry(
        outputVoltage = voltage.toDouble(),
        outputCurrent = current.toDouble(),
        power = power.toDouble(),
        // Target/Ah/Wh get the same treatment temperature already had. The `power == V * I`
        // invariant only vouches for the first three floats; these three sit past it and a
        // frame that passed the check with a garbage tail would otherwise put the tail
        // straight on a dashboard tile. Out of range reads as unknown, not as a number.
        targetVoltage = frame.floatLeAt(targetVoltageOffset)
            .withinOrNaN(0f, SKAT_MAX_PLAUSIBLE_VOLTAGE),
        ampHours = frame.floatLeAt(ampHoursOffset)
            .withinOrNaN(0f, SKAT_MAX_PLAUSIBLE_AMP_HOURS),
        wattHours = frame.floatLeAt(wattHoursOffset)
            .withinOrNaN(0f, SKAT_MAX_PLAUSIBLE_WATT_HOURS),
        temperature = if (plausibleTemperature) temperature.toDouble() else Double.NaN,
        rawFrame = frame,
    )
}

/** The value as a Double, or NaN when it is not finite or falls outside [min]..[max]. */
private fun Float.withinOrNaN(min: Float, max: Float): Double {
    return if (isFinite() && this >= min && this <= max) toDouble() else Double.NaN
}

/**
 * `power == V * I` to within float rounding, with a small floor so a genuine idle frame
 * (near-zero current) is not rejected by relative error alone.
 */
private fun isConsistentPower(voltage: Float, current: Float, power: Float): Boolean {
    if (!power.isFinite()) return false
    val expected = voltage * current
    val tolerance = maxOf(1.0f, kotlin.math.abs(power) * SKAT_POWER_TOLERANCE_FRACTION)
    return kotlin.math.abs(power - expected) <= tolerance
}

/**
 * Splits a byte stream into whole SKAT frames. Sync is the marker byte plus the fixed length for
 * that marker; a candidate that fails [decodeSkatChargerFrame] (bad `power == V * I`) is skipped a
 * byte at a time, so a marker value that merely lands inside float data does not manufacture a
 * frame. A marker whose frame has not fully arrived yet is left in the buffer for the next chunk.
 */
fun reassembleSkatChargerFrames(buffer: ByteArray): SkatChargerReassemblyResult {
    val frames = mutableListOf<SkatChargerFrame>()
    var offset = 0

    while (offset < buffer.size) {
        val marker = buffer[offset].toInt() and 0xFF
        val frameSize = when (marker) {
            SKAT_TELEMETRY_MARKER -> SKAT_TELEMETRY_FRAME_SIZE
            SKAT_STATUS_MARKER -> SKAT_STATUS_FRAME_SIZE
            else -> {
                offset++
                continue
            }
        }
        if (buffer.size - offset < frameSize) {
            // Head of a frame still arriving - keep it for the next chunk.
            break
        }
        val candidate = buffer.copyOfRange(offset, offset + frameSize)
        if (decodeSkatChargerFrame(candidate) == null) {
            offset++
            continue
        }
        frames += SkatChargerFrame(marker = marker, bytes = candidate)
        offset += frameSize
    }

    return SkatChargerReassemblyResult(
        frames = frames,
        remainingBuffer = buffer.copyOfRange(offset, buffer.size),
    )
}

private fun ByteArray.floatLeAt(offset: Int): Float {
    val bits = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits)
}

/** Notify characteristic seen in the capture; telemetry streams here unprompted. */
const val SKAT_CHARGER_NOTIFY_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"

/** Write characteristic the vendor app sends settings to (service FFF0, notify FFF1). */
const val SKAT_CHARGER_WRITE_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

/** `FA` marker: 25-byte live-telemetry frame. */
const val SKAT_TELEMETRY_MARKER = 0xFA
const val SKAT_TELEMETRY_FRAME_SIZE = 25

/** `FB` marker: 99-byte extended-status frame. */
const val SKAT_STATUS_MARKER = 0xFB
const val SKAT_STATUS_FRAME_SIZE = 99

/**
 * In the `FB` frame the six telemetry floats begin after a 31-byte header of int32 counters, and
 * temperature (~50 C, matching the vendor app's temperature tile) sits one float past the target
 * voltage.
 */
const val SKAT_STATUS_TELEMETRY_OFFSET = 31
const val SKAT_STATUS_TEMPERATURE_OFFSET = 43

/** How far `power` may drift from `V * I` before a frame is treated as spurious. */
const val SKAT_POWER_TOLERANCE_FRACTION = 0.02f

/** Plausibility bounds that reject a desynced fragment even when its power happened to check out. */
const val SKAT_MAX_PLAUSIBLE_VOLTAGE = 600f
const val SKAT_MAX_PLAUSIBLE_CURRENT = 500f
const val SKAT_MIN_PLAUSIBLE_TEMPERATURE = -40f
const val SKAT_MAX_PLAUSIBLE_TEMPERATURE = 200f

/**
 * Session counters, bounded generously rather than tightly: the point is to reject a float
 * that is not a measurement at all (1e24, a negative count), not to second-guess how long a
 * charge ran. The largest module in the range is 215 A, so 2000 Ah is many hours of it.
 */
const val SKAT_MAX_PLAUSIBLE_AMP_HOURS = 2_000f
const val SKAT_MAX_PLAUSIBLE_WATT_HOURS = 500_000f

// ---------------------------------------------------------------------------
// Charge-critical settings writes
//
// Every command below is copied from the vendor mini program's own code
// (`assets/SaaA_embed/…wxapkg` -> app-service.js), where a setting is sent as
// `an("<CMD>" + ln(value))` followed by an apply command `an("FFxx")`. `ln`
// encodes floor(value * 100) as a 4-byte big-endian integer, so 173.40 V ->
// 0x000043BC. Writes go to characteristic FFF2 as raw bytes, no checksum.
//
// Only charge-critical settings are exposed; the mini program has dozens more
// (screen, logo, Wi-Fi, calibration) that are deliberately omitted. Ranges are
// the vendor UI's own min/max where it stated them; a value outside the range
// yields null rather than a clamped write, because a charger is not a safe place
// to guess.
// ---------------------------------------------------------------------------

/** The ×100, 4-byte big-endian value encoding the vendor app calls `ln`. */
fun encodeSkatSettingValue(value: Double): ByteArray {
    val scaled = kotlin.math.floor(value * 100.0).toLong()
    return byteArrayOf(
        ((scaled ushr 24) and 0xFF).toByte(),
        ((scaled ushr 16) and 0xFF).toByte(),
        ((scaled ushr 8) and 0xFF).toByte(),
        (scaled and 0xFF).toByte(),
    )
}

enum class SkatChargeSetting(
    val key: String,
    /** The setter command bytes that precede the encoded value. */
    val commandCode: Int,
    /** The apply command(s) the vendor app sends after the setter. */
    val applyCodes: List<Int>,
    val minValue: Double,
    val maxValue: Double,
) {
    SecondStageVoltage("skat_second_stage_voltage", 0xF858, listOf(0xFF12), 10.0, 1000.0),
    SecondStageCurrent("skat_second_stage_current", 0xF857, listOf(0xFF12), 1.0, 400.0),
    MaxVoltage("skat_max_voltage", 0xF8FA, listOf(0xFF19, 0xFF14), 100.0, 1000.0),
    MaxCurrent("skat_max_current", 0xF8F9, listOf(0xFF19, 0xFF14), 10.0, 400.0),
    PreChargeVoltage("skat_pre_charge_voltage", 0xF8F6, listOf(0xFF), 10.0, 1000.0),
    PreChargeCurrent("skat_pre_charge_current", 0xF8F5, listOf(0xFF), 1.0, 400.0),
    OverTemperatureLimit("skat_over_temp_limit", 0xF84A, listOf(0xFF13), 40.0, 90.0),
    OverTemperatureExit("skat_over_temp_exit", 0xF84B, listOf(0xFF13), 40.0, 90.0);
}

/**
 * The BLE writes for one charge-critical setting: the setter frame carrying the ×100 value,
 * then the vendor's apply frame(s). Returns null when the value is outside the vendor range,
 * which the caller must surface rather than clamp.
 */
@ExperimentalWriteApi
fun skatChargeSettingCommands(setting: SkatChargeSetting, value: Double): List<ByteArray>? {
    if (!value.isFinite() || value < setting.minValue || value > setting.maxValue) return null
    val setter = commandCodeBytes(setting.commandCode) + encodeSkatSettingValue(value)
    val applies = setting.applyCodes.map { commandCodeBytes(it) }
    return listOf(setter) + applies
}

/** A 1- or 2-byte command code as bytes: 0xFF -> [FF], 0xF858 -> [F8, 58]. */
private fun commandCodeBytes(code: Int): ByteArray {
    return if (code <= 0xFF) {
        byteArrayOf((code and 0xFF).toByte())
    } else {
        byteArrayOf(((code ushr 8) and 0xFF).toByte(), (code and 0xFF).toByte())
    }
}
