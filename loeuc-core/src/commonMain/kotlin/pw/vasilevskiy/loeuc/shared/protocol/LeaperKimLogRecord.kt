package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/**
 * One entry of the LeaperKim event log, as it arrives inside an ordinary `DC 5A 5C` frame.
 *
 * The wheel does not ship the log over a side channel: it reuses the normal telemetry frame and
 * swaps the extension for page `33`. That is why reading the log does not kill telemetry — the main
 * frame body (`4..45`: speed, voltage, phase current, board temperature, PWM) is built on every
 * frame regardless of page, and only the extension at `46..` changes. Pages `0..7` stop for the
 * duration, page `8` still arrives every twentieth frame.
 *
 * Firmware side, for `0090.04`: `FUN_08010BA0` walks a cursor at `0x2000032C + 4` upward and reads
 * one record per frame; `FUN_0801914C` branch `0x21` packs it; `FUN_08010C04` is the scheduler that
 * substitutes page `33` while the cursor is armed. The offsets below are the app's own parse
 * (`BtManager`, the `bByteValue2 == 33` branch), which agrees with the firmware packer.
 */
data class LeaperKimLogRecord(
    /** Records ever written. Monotonic: the firmware increments it without wrapping. */
    val totalCount: Int,
    /** Cursor position of this record, 1-based. */
    val index: Int,
    /**
     * Four raw timestamp bytes: Unix seconds, big-endian, the way the official app reads them
     * (`LogItemInfo.getShowTime`, `bytesToLong` then `formatTimeWithSeconds(t * 1000)`). Zero
     * means the wheel had no clock when it logged the event.
     */
    val timeBytes: List<Int>,
    /** The event code the firmware logged this record under. */
    val code: Int,
    /** Up to eight signed 32-bit values; the wheel clamps the count to 8. */
    val values: List<Long>,
    /** The event's own name, e.g. `mCoilOver166`. Non-ASCII bytes are escaped, not dropped. */
    val text: String,
) {
    /** [timeBytes] as Unix seconds, or null when the wheel logged no time (all zero). */
    val unixSeconds: Long?
        get() {
            val value = timeBytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
            return value.takeIf { it != 0L }
        }

    /** True for the marker the wheel sends once its cursor runs out, not for an event. */
    val isEndMarker: Boolean
        get() = index == LEAPERKIM_LOG_END_INDEX_PAGE33 && totalCount >= 0 ||
            totalCount < 0 && index == LEAPERKIM_LOG_END_INDEX_PAGE32

    /** The header line the debug screen shows: everything except the values. */
    val summary: String
        get() = "#$index/$totalCount code=$code time=${timeBytes.joinToString(".")}"
}

private const val LOG_PAGE_ID = 33
private const val LOG_PAGE_32_ID = 32
private const val PAGE_ID_OFFSET = 46

/**
 * Index of the page-33 record that ends a read: the cursor parked at `0xFFF`. The official app
 * stops on it (`LogUploadActivity.onReceiveLog`, `isMode33 && index == 4095`) and sends nothing.
 */
const val LEAPERKIM_LOG_END_INDEX_PAGE33 = 4095

/** The same end marker for the older page-32 log, whose index is one byte. */
const val LEAPERKIM_LOG_END_INDEX_PAGE32 = 255

/**
 * Parses the older page-`32` log out of a reassembled `DC 5A 5C` frame: up to three records per
 * frame, each an index, a two-byte event code, four time bytes and five extra bytes. Returns an
 * empty list for any other page or a frame too short to carry them.
 *
 * Offsets follow the official app (`BtManager.handleFullSingleData`, `bByteValue2 == 32`),
 * including its guard `size - 4 > 80`. Records from this page have no total count and no text,
 * so [LeaperKimLogRecord.totalCount] is `-1` and the five extras are carried as [values].
 */
fun parseLeaperKimLogPage32Records(frame: ByteArray): List<LeaperKimLogRecord> {
    if (frame.size <= PAGE_ID_OFFSET) return emptyList()
    if (frame[PAGE_ID_OFFSET].toInt() and 0xFF != LOG_PAGE_32_ID) return emptyList()
    if (frame.size - 4 <= 80) return emptyList()

    fun byte(index: Int): Int = frame[index].toInt() and 0xFF
    fun signed(index: Int): Long = frame[index].toLong()

    val first = byte(47)
    return (0 until 3).mapNotNull { slot ->
        val index = first + slot
        // The app emits the second record only below 255 and the third only below 254.
        if (slot > 0 && index > LEAPERKIM_LOG_END_INDEX_PAGE32) return@mapNotNull null
        val base = 48 + slot * 11
        LeaperKimLogRecord(
            totalCount = -1,
            index = index,
            timeBytes = listOf(byte(base + 2), byte(base + 3), byte(base + 4), byte(base + 5)),
            code = (byte(base) shl 8) or byte(base + 1),
            values = (base + 6..base + 10).map(::signed),
            text = "",
        )
    }
}

/**
 * Parses a page-`33` record out of a reassembled `DC 5A 5C` frame, or returns null when the frame is
 * not a log page or is too short. [frame] must still carry its four trailing CRC bytes, because the
 * length guard mirrors the wheel's own.
 */
fun parseLeaperKimLogRecord(frame: ByteArray): LeaperKimLogRecord? {
    if (frame.size <= PAGE_ID_OFFSET) return null
    if (frame[PAGE_ID_OFFSET].toInt() and 0xFF != LOG_PAGE_ID) return null
    // The wheel's own guard. Below it the record is a stub and every field would read as garbage.
    if (frame.size - 4 <= 56) return null

    fun byte(index: Int): Int = frame[index].toInt() and 0xFF

    val totalCount = byte(47) * 16 + byte(48) / 16
    val index = (byte(48) % 16) * 256 + byte(49)
    val timeBytes = listOf(byte(50), byte(51), byte(52), byte(53))
    val code = (byte(54) shl 8) or byte(55)
    val declaredCount = byte(56)

    val values = ArrayList<Long>(declaredCount)
    for (i in 0 until declaredCount) {
        val at = 57 + i * 4
        if (at + 3 >= frame.size) break
        var value = (byte(at).toLong() shl 24) or
            (byte(at + 1).toLong() shl 16) or
            (byte(at + 2).toLong() shl 8) or
            byte(at + 3).toLong()
        if (value > 2147483648L) value -= 4294967296L
        values.add(value)
    }

    val textStart = 57 + values.size * 4
    val builder = StringBuilder()
    var at = textStart
    while (at < frame.size) {
        val raw = byte(at)
        if (raw == 0) break
        // The wheel encodes this as GBK. Event names are ASCII; anything else is shown as a byte
        // escape rather than silently mangled into the wrong glyph.
        if (raw in 0x20..0x7E) builder.append(raw.toChar()) else builder.append("\\x").append(raw.toHexPair())
        at++
    }

    return LeaperKimLogRecord(
        totalCount = totalCount,
        index = index,
        timeBytes = timeBytes,
        code = code,
        values = values,
        text = builder.toString(),
    )
}

/**
 * One read of the event log, accumulated out of the live byte stream.
 *
 * The wheel does not announce where the log ends: the records simply stop arriving, and a cursor
 * that ran out is reported once as an end marker. So a reader needs three things at the same time
 * - the records so far, keyed by index because the same record arrives again after a retry; the
 * fact that the end marker went by; and what came from the wheel instead of records, which is the
 * only thing that separates "the wheel is silent" from "the wheel is talking but not sending the
 * log". All three live here so the phone shells do not each grow their own copy.
 *
 * Both pages the official app knows are accepted, as on Android's screen: `33` carries one record
 * with its text per frame, the older `32` three records without text.
 */
class LeaperKimLogSession {

    private val reassembler = LeaperKimFrameReassembler()
    private val byIndex = mutableMapOf<Int, LeaperKimLogRecord>()
    private val pages = mutableMapOf<Int, Int>()

    /** Frames that arrived since [reset], of any page. */
    var framesSeen: Int = 0
        private set

    /** True once the wheel sent the end marker: its cursor ran out and no record follows. */
    var isEnded: Boolean = false
        private set

    /** Starts a new read. The half-assembled tail of the previous one goes with it. */
    fun reset() {
        reassembler.reset()
        byIndex.clear()
        pages.clear()
        framesSeen = 0
        isEnded = false
    }

    /** Feeds one notification. Frames it completes are counted and parsed. */
    fun consume(chunk: ByteArray) {
        reassembler.consume(chunk).forEach(::consumeFrame)
    }

    /** Feeds one already reassembled frame, for a caller that keeps its own capture. */
    fun consumeFrame(frame: ByteArray) {
        framesSeen++
        if (frame.size > PAGE_ID_OFFSET) {
            val page = frame[PAGE_ID_OFFSET].toInt() and 0xFF
            pages[page] = (pages[page] ?: 0) + 1
        }
        val parsed = parseLeaperKimLogRecord(frame)?.let(::listOf)
            ?: parseLeaperKimLogPage32Records(frame)
        parsed.forEach { record ->
            if (record.isEndMarker) isEnded = true else byIndex[record.index] = record
        }
    }

    /** What the wheel has handed over so far, in cursor order. */
    val records: List<LeaperKimLogRecord>
        get() = byIndex.values.sortedBy { it.index }

    /**
     * Which pages arrived while reading, e.g. `"0x40, 8x2"` - the diagnostics line shown when no
     * record came. The separator is the multiplication sign, as on Android's screen.
     */
    fun pagesSummary(): String =
        pages.entries.sortedBy { it.key }.joinToString(", ") { (page, count) -> "$page×$count" }

    companion object {
        /**
         * Silence this long with not one record means the wheel never answered.
         *
         * Longer than [EndOfLogTimeoutMillis] because the first record has to wait for the wheel's
         * scheduler to swap page `33` in, while every later one follows the previous frame.
         */
        const val NoAnswerTimeoutMillis: Long = 6_000

        /** Silence this long after a record is the end of the log. */
        const val EndOfLogTimeoutMillis: Long = 3_000

        /** How long to wait for the next record, given how many have arrived. */
        fun timeoutMillis(recordCount: Int): Long =
            if (recordCount == 0) NoAnswerTimeoutMillis else EndOfLogTimeoutMillis
    }
}

private fun Int.toHexPair(): String {
    val digits = "0123456789ABCDEF"
    return "${digits[(this shr 4) and 0xF]}${digits[this and 0xF]}"
}

/**
 * The command that arms the log cursor, byte-identical to what the official app sends.
 *
 * `BtManager.CMD_READ_LOG` and `CMD_READ_LOG_NEW`, each with its own CRC32, concatenated — the
 * `sendBytesDataCombine` shape the app uses for every command that has both a legacy `LkAp` and a
 * modern `LdAp` form. The trailing `0x01` is the value that reaches `FUN_080282DC`, which resets the
 * cursor to zero; `0xFF` there parks it again and is what [buildLeaperKimStopLogCommand] sends.
 */
@ExperimentalWriteApi
fun buildLeaperKimReadLogCommand(): ByteArray = logCommand(0x01)

/** Parks the log cursor, so the scheduler goes back to emitting pages `0..7`. */
@ExperimentalWriteApi
fun buildLeaperKimStopLogCommand(): ByteArray = logCommand(0xFF)

private fun logCommand(value: Int): ByteArray {
    val legacy = byteArrayOf(
        'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
        0x14, 0x01,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        value.toByte(),
    )
    val modern = byteArrayOf(
        'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
        0x14, 0x01, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        value.toByte(),
    )
    return legacy.withLogCrc32() + modern.withLogCrc32()
}

private fun ByteArray.withLogCrc32(): ByteArray {
    var crc = 0xFFFFFFFFL
    for (byte in this) {
        crc = crc xor (byte.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
        }
    }
    val value = crc xor 0xFFFFFFFFL
    return this + byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
