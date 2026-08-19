package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Transport shape of the LeaperKim FFE1 notify stream.
 *
 * The wheel multiplexes several unrelated producers onto the same characteristic, and which one is
 * talking is the only reliable way to tell what diagnostic state the wheel is in. Firmware `0090.04`
 * gates the diagnostic printer on the byte at `0x20000030`, incremented by one `CHANGESTRORPACK`:
 * at `<= 2` the record goes out as raw characters, at `>= 3` it is wrapped in 70-byte `StR` packets,
 * and the record builder itself only runs when the byte is exactly `3`. So [Framed] is the state
 * worth stopping at: capture `20260810_191050` held it for 75 s untouched, while one increment past
 * it dropped into [Unknown] about six seconds later.
 *
 * See `docs/leaperkim-str-debug-telemetry-ru.md`.
 */
enum class LeaperKimStrTransport {
    /** No bytes in the window. */
    Idle,

    /** Ordinary `DC 5A 5C` telemetry frames. The diagnostic stream is not engaged. */
    NormalFrames,

    /** Diagnostic records as bare characters, no packet header. Mode byte is at most 2. */
    RawText,

    /** Diagnostic records inside `53 74 52 7F 00 40` packets. Mode byte is at least 3. */
    Framed,

    /** Bytes are arriving but match no known producer. All three 2026-08-10 captures ended here. */
    Unknown,
}

data class LeaperKimStrStreamState(
    val transport: LeaperKimStrTransport,
    /** Complete `StR` packet headers seen in the window. */
    val strPacketCount: Int,
    /** Diagnostic record starts seen in the window, after packet headers are stripped. */
    val recordCount: Int,
    /**
     * The `/N` field of the most recent record header, when one is readable. It is the RTC weekday
     * with Monday as 1, not the diagnostic page: three captures across two dates match
     * (2026-08-10 -> `/1`, 2026-08-11 -> `/2`), and it did not move when `CHANGESHOWPAGE` changed
     * the printed field set. Useful only as a sign that the header parsed at all.
     */
    val headerWeekday: Int?,
    val bytesInWindow: Int,
)

/**
 * Classifies the live notify stream over a short rolling window.
 *
 * Pure logic: feed it every notify payload with the timestamp it arrived at, then ask [state].
 * The window is trimmed by both age and size, so a detector left running costs a bounded amount of
 * memory regardless of throughput.
 */
class LeaperKimStrStreamDetector(
    private val windowMillis: Long = DefaultWindowMillis,
    private val maxWindowBytes: Int = DefaultMaxWindowBytes,
) {
    private class Chunk(val timestampMillis: Long, val bytes: ByteArray)

    private val chunks = ArrayDeque<Chunk>()
    private var bufferedBytes = 0

    fun reset() {
        chunks.clear()
        bufferedBytes = 0
    }

    fun ingest(bytes: ByteArray, timestampMillis: Long) {
        if (bytes.isEmpty()) return
        chunks.addLast(Chunk(timestampMillis, bytes.copyOf()))
        bufferedBytes += bytes.size
        trim(timestampMillis)
    }

    fun state(nowMillis: Long): LeaperKimStrStreamState {
        trim(nowMillis)
        val window = window()
        if (window.isEmpty()) {
            return LeaperKimStrStreamState(
                transport = LeaperKimStrTransport.Idle,
                strPacketCount = 0,
                recordCount = 0,
                headerWeekday = null,
                bytesInWindow = 0,
            )
        }

        val headerOffsets = window.offsetsOf(StrPacketHeader)
        val text = window.withoutStrHeaders()
        val recordOffsets = text.recordStartOffsets()
        val transport = when {
            headerOffsets.looksLikeStrPackets() -> LeaperKimStrTransport.Framed
            window.looksLikeNormalFrames() -> LeaperKimStrTransport.NormalFrames
            recordOffsets.isNotEmpty() && text.printableRatio() > RawTextPrintableRatio ->
                LeaperKimStrTransport.RawText
            else -> LeaperKimStrTransport.Unknown
        }
        return LeaperKimStrStreamState(
            transport = transport,
            strPacketCount = headerOffsets.size,
            recordCount = recordOffsets.size,
            headerWeekday = text.weekdayAt(recordOffsets.lastOrNull()),
            bytesInWindow = window.size,
        )
    }

    private fun trim(nowMillis: Long) {
        while (chunks.isNotEmpty() && nowMillis - chunks.first().timestampMillis > windowMillis) {
            bufferedBytes -= chunks.removeFirst().bytes.size
        }
        while (chunks.size > 1 && bufferedBytes > maxWindowBytes) {
            bufferedBytes -= chunks.removeFirst().bytes.size
        }
    }

    private fun window(): ByteArray {
        if (chunks.isEmpty()) return ByteArray(0)
        val out = ByteArray(bufferedBytes)
        var at = 0
        for (chunk in chunks) {
            chunk.bytes.copyInto(out, at)
            at += chunk.bytes.size
        }
        return out
    }

    companion object {
        const val DefaultWindowMillis: Long = 4_000
        const val DefaultMaxWindowBytes: Int = 16_384

        /** `"StR" 7F 00 40`, written by the packetizer once its 64-byte payload buffer fills. */
        val StrPacketHeader: ByteArray = byteArrayOf(0x53, 0x74, 0x52, 0x7F, 0x00, 0x40)

        /** Header plus payload. The packetizer never emits a short packet. */
        const val StrPacketLength: Int = 70
    }
}

private val FrameMarker = byteArrayOf(0xDC.toByte(), 0x5A, 0x5C)

/** Shortest declared frame seen in captures: legacy Sherman Max, length byte `0x20`. */
private const val MinFrameLength = 36
private const val RawTextPrintableRatio = 0.85

private val RecordStartMarker = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

/** `\r\n\r\n` (4) plus `2026-08-10 22:27:23` (19), so the `/N` field lands here. */
private const val RecordWeekdayOffset = 23

private fun ByteArray.matchesAt(offset: Int, pattern: ByteArray): Boolean {
    if (offset < 0 || offset + pattern.size > size) return false
    for (index in pattern.indices) {
        if (this[offset + index] != pattern[index]) return false
    }
    return true
}

private fun ByteArray.offsetsOf(pattern: ByteArray): List<Int> {
    val out = mutableListOf<Int>()
    var offset = 0
    while (offset + pattern.size <= size) {
        if (matchesAt(offset, pattern)) {
            out += offset
            offset += pattern.size
        } else {
            offset++
        }
    }
    return out
}

/**
 * True when the header offsets are spaced the way the packetizer spaces them. Requires more than one
 * header so that a single `StR` byte sequence appearing inside unrelated data cannot pass.
 */
private fun List<Int>.looksLikeStrPackets(): Boolean {
    if (size < 2) return false
    var matching = 0
    for (index in 0 until size - 1) {
        if (this[index + 1] - this[index] == LeaperKimStrStreamDetector.StrPacketLength) matching++
    }
    return matching * 2 >= size - 1
}

/**
 * True when at least two frames chain through the declared length at byte 3, which is how the frame
 * sender computes it (`len = buf[3] + 4`). A lone marker is not enough: three bytes recur in
 * high-entropy data often enough to matter.
 */
private fun ByteArray.looksLikeNormalFrames(): Boolean {
    var offset = 0
    while (offset + FrameMarker.size <= size) {
        if (matchesAt(offset, FrameMarker) && chainsFrom(offset)) return true
        offset++
    }
    return false
}

private fun ByteArray.chainsFrom(start: Int): Boolean {
    var offset = start
    var chained = 0
    while (offset + 4 <= size) {
        val declared = (this[offset + 3].toInt() and 0xFF) + 4
        if (declared < MinFrameLength) return false
        val next = offset + declared
        if (!matchesAt(next, FrameMarker)) return chained >= 1
        chained++
        offset = next
    }
    return chained >= 1
}

/**
 * The window with every packet header removed, so record text can be read across packet boundaries.
 * A header can land anywhere inside a record, including mid-number.
 */
private fun ByteArray.withoutStrHeaders(): ByteArray {
    val header = LeaperKimStrStreamDetector.StrPacketHeader
    val out = ByteArray(size)
    var read = 0
    var write = 0
    while (read < size) {
        if (matchesAt(read, header)) {
            read += header.size
        } else {
            out[write++] = this[read++]
        }
    }
    return out.copyOf(write)
}

private fun ByteArray.recordStartOffsets(): List<Int> =
    offsetsOf(RecordStartMarker).filter { offset ->
        val digit = offset + RecordStartMarker.size
        digit < size && this[digit] >= '0'.code.toByte() && this[digit] <= '9'.code.toByte()
    }

private fun ByteArray.weekdayAt(recordStart: Int?): Int? {
    if (recordStart == null) return null
    var offset = recordStart + RecordWeekdayOffset
    if (offset >= size || this[offset] != '/'.code.toByte()) return null
    offset++
    var value = 0
    var digits = 0
    while (offset < size && this[offset] >= '0'.code.toByte() && this[offset] <= '9'.code.toByte()) {
        value = value * 10 + (this[offset] - '0'.code.toByte())
        offset++
        digits++
        if (digits > 3) return null
    }
    return if (digits == 0) null else value
}

private fun ByteArray.printableRatio(): Double {
    if (isEmpty()) return 0.0
    var printable = 0
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        if (value in 0x20..0x7E || value == 0x0D || value == 0x0A) printable++
    }
    return printable.toDouble() / size
}
