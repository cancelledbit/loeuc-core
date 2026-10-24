package pw.vasilevskiy.loeuc.shared.protocol

/** One temperature sensor inside an InMotion battery pack. */
data class InmotionBmsTemperature(
    val index: Int,
    val celsius: Double,
)

/**
 * One answer to a direct BMS read: the wheel forwards what the pack's own BMS replied.
 *
 * The frame is a many-to-many envelope, so the three bytes behind the length are a
 * source/target/command triple rather than the encoded command field every other InMotion
 * frame carries. [payload] is what the BMS said; [rawFrame] is kept because a diagnostics
 * screen has to be able to show bytes nobody has decoded yet.
 */
data class InmotionDirectBmsResponse(
    val sourceAddress: Int,
    val targetAddress: Int,
    val selector: Int,
    val payload: ByteArray,
    val rawFrame: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        return other is InmotionDirectBmsResponse &&
            sourceAddress == other.sourceAddress &&
            targetAddress == other.targetAddress &&
            selector == other.selector &&
            payload.contentEquals(other.payload) &&
            rawFrame.contentEquals(other.rawFrame)
    }

    override fun hashCode(): Int {
        var result = sourceAddress
        result = 31 * result + targetAddress
        result = 31 * result + selector
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + rawFrame.contentHashCode()
        return result
    }
}

/**
 * The `general` block of a selector-1 answer - everything the pack reports about itself
 * except the per-cell grid, which is a separate read.
 *
 * A field is null where this firmware leaves it at a hard zero or a sentinel: a pack that
 * has never been charged and a pack whose cycle counter is simply not filled look the same
 * on the wire, and reporting the second as "0 cycles" states something the wheel did not.
 */
data class InmotionDirectBmsRealtime(
    val chargeCurrentAmps: Double?,
    val dischargeCurrentAmps: Double?,
    val temperatures: List<InmotionBmsTemperature>,
    val maxCellVoltage: Double? = null,
    val minCellVoltage: Double? = null,
    val fullCapacityAh: Double? = null,
    val remainCapacityAh: Double? = null,
    val fullChargeCycles: Int? = null,
    val chargeMosTemperature: Double? = null,
    val dischargeMosTemperature: Double? = null,
    val boardTemperature1: Double? = null,
    val boardTemperature2: Double? = null,
    val relativeHumidityPercent: Double? = null,
    val chargeMosOn: Boolean? = null,
    val dischargeMosOn: Boolean? = null,
    val cellBalancing: Boolean? = null,
) {
    /** Charge level of this pack, the way the official app derives it. */
    val capacityLevelPercent: Double?
        get() {
            val full = fullCapacityAh?.takeIf { it > 0.0 } ?: return null
            val remain = remainCapacityAh ?: return null
            return (remain / full * 100.0).coerceIn(0.0, 100.0)
        }
}

/**
 * Direct BMS reads: the wheel is asked to relay a question to a pack's own BMS, and the
 * answer comes back in a many-to-many frame the ordinary command dispatch never sees.
 *
 * This is the only source of per-cell voltages and pack temperatures on a P6 - the plain
 * `0x05` battery answer carries neither - which is why the decode lives next to the engine
 * rather than in an application.
 */
object InmotionDirectBms {
    /** Pack realtime block: currents, capacities, cell spread and the temperature array. */
    const val SELECTOR_REALTIME = 0x01

    /** Per-cell voltage array. */
    const val SELECTOR_CELLS = 0x02

    /**
     * A third read the wheel answers and nothing here decodes. It stays in the request set
     * because the diagnostics screen shows its bytes, which is how the other two were read.
     */
    const val SELECTOR_UNDECODED = 0x04

    /** BMS addresses `TargetDeviceType` lists, unshifted, as they go on the wire. */
    val ADDRESSES: List<Int> = listOf(0x24, 0x25, 0x26, 0x27, 0x32, 0x34)

    /** Reads that have been seen to answer. */
    val SELECTORS: List<Int> = listOf(SELECTOR_REALTIME, SELECTOR_CELLS, SELECTOR_UNDECODED)

    /**
     * Asks one BMS one question. Nothing about the wheel changes: the command byte carries no
     * `0x80`, and that bit is what marks a write.
     *
     * The target goes on the wire unshifted - `TargetDeviceType` stores the addresses directly,
     * and the `lsl x, y, #1` that an earlier reading took for a protocol shift is Dart's Smi
     * tagging, applied by the same code to a byte it has just read off the wire.
     */
    fun readCommand(targetAddress: Int, selector: Int): ByteArray {
        require(targetAddress in 0..0x7F)
        require(selector in 0..0x1F)
        return buildInmotionV2Message(
            flag = FLAG_MANY_TO_MANY_WIRE,
            command = 0x02,
            data = byteArrayOf(targetAddress.toByte(), selector.toByte()),
        )
    }

    /**
     * Splits one already-unescaped frame into the response it carries, or returns null when
     * this is not a direct BMS answer.
     *
     * The direction matters: the same envelope carries the request this application sent, and
     * a request has `0x02` - the source address the app hardcodes for itself - in the source
     * byte. Only a frame coming *from* a BMS address *to* `0x02` is an answer.
     */
    fun response(frame: ByteArray): InmotionDirectBmsResponse? {
        if (frame.size < 8 || frame.u8(2) != FLAG_MANY_TO_MANY_WIRE || frame.u8(3) < 3) {
            return null
        }
        val sourceAddress = frame.u8(4)
        val targetAddress = frame.u8(5)
        // The high bits of the command byte mark a write and the frame's own bookkeeping; the
        // read being answered is the low five.
        val selector = frame.u8(6) and 0x1F
        if (sourceAddress !in ADDRESSES || targetAddress != HOST_ADDRESS || selector !in SELECTORS) {
            return null
        }
        val payloadEnd = minOf(4 + frame.u8(3), frame.size - 1)
        if (payloadEnd <= DIRECT_PAYLOAD_START) {
            return null
        }
        return InmotionDirectBmsResponse(
            sourceAddress = sourceAddress,
            targetAddress = targetAddress,
            selector = selector,
            payload = frame.copyOfRange(DIRECT_PAYLOAD_START, payloadEnd),
            rawFrame = frame.copyOf(),
        )
    }

    /**
     * Per-cell voltages of a selector-2 answer: little-endian millivolts, nothing else.
     *
     * The whole array has to be plausible or none of it is taken. There is no length field
     * and no marker here, so a payload that is in fact some other layout would otherwise be
     * printed as a battery - and a cell grid is exactly the screen a rider reads to decide
     * whether a pack is dying.
     */
    fun cells(payload: ByteArray): List<Double> {
        if (payload.size < 2 || payload.size % 2 != 0) return emptyList()
        val cells = (payload.indices step 2).map { offset ->
            payload.uint16Le(offset) / 1000.0
        }
        return cells.takeIf { values -> values.all { it in MIN_CELL_VOLTS..MAX_CELL_VOLTS } }.orEmpty()
    }

    /**
     * The `general` block of a selector-1 answer.
     *
     * A selector-1 response is `format`, `datetime`, `general`, then state/warning/
     * protection/error - the field order of `LorinRealtimeBMSPackInfo`. On a real P6 the
     * prefix is six bytes, so `P6BMSGeneral` starts at payload offset 6.
     *
     * Confirmed against two BMS units answering in the same capture: at that offset the
     * voltage reads 228.36 V and 228.08 V, matching the pack voltage the plain `0x05`
     * battery response reported on the same wheel, and the max/min cell voltages land on
     * 4078/4073 mV and 4080/4076 mV.
     */
    fun realtime(payload: ByteArray): InmotionDirectBmsRealtime? {
        val offset = GENERAL_OFFSET
        if (payload.size < offset + GENERAL_SIZE) {
            return null
        }
        // Anchor check: the pack voltage has to be physically sane, otherwise this is not a
        // layout we understand and guessing would print nonsense.
        val voltage = payload.uint16Le(offset) / 100.0
        if (voltage !in MIN_PACK_VOLTS..MAX_PACK_VOLTS) {
            return null
        }
        // Offsets are `LorinBMSGeneral.fromBytes` joined with its `toMap` keys, so they are
        // named by the app rather than guessed: voltage @0, charge current @2, discharge
        // current @4, full capacity @6, remaining capacity @8, full charge cycles @10, max
        // cell mV @12, min cell mV @14. `P6BMSGeneral` adds its own `sop` i16 at @16, then
        // four biased-byte temperatures at @18 - charge MOS, discharge MOS and two board
        // sensors - followed by the per-cell temperature array, where a raw zero means "no
        // sensor" and ends the used part.
        val cellTemperatureBase = offset + GENERAL_SIZE
        val cellTemperatures = (cellTemperatureBase..payload.lastIndex)
            .asSequence()
            .map { index -> index to payload[index].toInt() }
            .takeWhile { (_, raw) -> raw != 0 }
            .mapNotNull { (index, _) ->
                payload.bmsTemperature(index)?.let {
                    InmotionBmsTemperature(index - cellTemperatureBase + 1, it)
                }
            }
            .toList()
        return InmotionDirectBmsRealtime(
            // The P6 BMS leaves its own current pair at a hard zero - six units across three
            // wheels have never reported anything else here, including a capture where the
            // plain `0x05` battery frame read -31 on both packs at the same moment. Reported
            // as absent so the `0x05` value wins the merge; a real zero is indistinguishable
            // and costs nothing, since the fallback also reads zero when the packs are
            // genuinely idle.
            chargeCurrentAmps = payload.int16Le(offset + 2).takeIf { it != 0 }?.div(100.0),
            dischargeCurrentAmps = payload.int16Le(offset + 4).takeIf { it != 0 }?.div(100.0),
            temperatures = cellTemperatures,
            // Millivolts, and they match the extremes of the selector-2 array exactly on both
            // units of the captured wheel. Worth having separately: this response is a fifth
            // of the size of the cell array, so the spread can be refreshed from it far more
            // often than the full per-cell grid.
            maxCellVoltage = payload.uint16LeOrNull(offset + 12)?.div(1000.0),
            minCellVoltage = payload.uint16LeOrNull(offset + 14)?.div(1000.0),
            // Capacities are milliamp-hours: the real response reads 10000/8600, so a /100
            // scale would claim a 100 Ah pack. The ratio - and therefore the derived charge
            // level - is the same either way; only the absolute figure depends on this, and
            // the unit is inferred from plausibility, not decoded.
            fullCapacityAh = payload.uint16LeOrNull(offset + 6)?.div(1000.0),
            remainCapacityAh = payload.uint16LeOrNull(offset + 8)?.div(1000.0),
            // `full_charge_cycles` per LorinBMSGeneral, and the offset is right - a real P6
            // just reports a hard zero here on both of its BMS units. Reported as absent
            // rather than as a wheel with no charge cycles.
            fullChargeCycles = payload.uint16LeOrNull(offset + 10)?.takeIf { it > 0 },
            chargeMosTemperature = payload.bmsTemperature(offset + 18),
            dischargeMosTemperature = payload.bmsTemperature(offset + 19),
            boardTemperature1 = payload.bmsTemperature(offset + 20),
            boardTemperature2 = payload.bmsTemperature(offset + 21),
            // Not reported by the P6. The tail behind the cell temperature array is a fixed
            // `00 00 FF FF 00 00 00 2A 00...` on every unit captured so far - six BMS across
            // three different wheels - so the `2A` that once looked like a plausible 42 % is
            // a constant, not a reading. `LorinBMSGeneral` puts `relative_humidity` right
            // after the array, which lands on the `FF FF` slot: the usual "sensor absent"
            // marker.
            relativeHumidityPercent = null,
            // MOS and balancing states belong to BMSState, a block this response does not
            // carry, and the legacy offsets for them pointed past its end.
            chargeMosOn = null,
            dischargeMosOn = null,
            cellBalancing = null,
        )
    }
}

/** The source address the official app hardcodes for itself; a BMS answers to it. */
private const val HOST_ADDRESS = 0x02

private const val FLAG_MANY_TO_MANY_WIRE = 0x16

/** Header, length, source, target and selector come before the payload. */
private const val DIRECT_PAYLOAD_START = 7

private const val GENERAL_OFFSET = 6
private const val GENERAL_SIZE = 22

private const val MIN_CELL_VOLTS = 2.0
private const val MAX_CELL_VOLTS = 5.0
private const val MIN_PACK_VOLTS = 50.0
private const val MAX_PACK_VOLTS = 300.0

/** BMS temperatures are a signed byte biased by +80 (`add #0x50` in the compact parser). */
private fun ByteArray.bmsTemperature(offset: Int): Double? {
    val raw = getOrNull(offset)?.toInt() ?: return null
    return (raw + 80).toDouble().takeIf { it in -40.0..120.0 }
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.uint16Le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.int16Le(offset: Int): Int {
    val value = uint16Le(offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun ByteArray.uint16LeOrNull(offset: Int): Int? {
    if (offset < 0 || offset + 1 > lastIndex) return null
    return uint16Le(offset)
}
