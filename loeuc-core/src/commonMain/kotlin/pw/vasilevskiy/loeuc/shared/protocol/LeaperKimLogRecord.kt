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
    /** Four raw timestamp bytes. Their encoding is not established. */
    val timeBytes: List<Int>,
    /** The event code the firmware logged this record under. */
    val code: Int,
    /** Up to eight signed 32-bit values; the wheel clamps the count to 8. */
    val values: List<Long>,
    /** The event's own name, e.g. `mCoilOver166`. Non-ASCII bytes are escaped, not dropped. */
    val text: String,
) {
    /** The header line the debug screen shows: everything except the values. */
    val summary: String
        get() = "#$index/$totalCount code=$code time=${timeBytes.joinToString(".")}"
}

private const val LOG_PAGE_ID = 33
private const val PAGE_ID_OFFSET = 46

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
