package pw.vasilevskiy.loeuc.shared.protocol

/**
 * One diagnostic record printed by LeaperKim firmware `0090.04`.
 *
 * The wheel prints a record every 500 ms. Which fields appear depends on the page selected by
 * `CHANGESHOWPAGE`; the page number itself is never printed, so a page is identified only by the
 * names it carries. See `docs/leaperkim-str-debug-telemetry-ru.md`.
 */
data class LeaperKimStrRecord(
    /** Arrival time of the delimiter that closed the record. */
    val timestampMillis: Long,
    /**
     * Field name to value, in the order the wheel printed them.
     *
     * A name the wheel prints twice in one record keeps the later value - the IMU page does exactly
     * that with `GZ`, which looks like a slip in the firmware rather than two channels.
     */
    val fields: Map<String, Double>,
    /** Wheel uptime from the `H:MM:SS` header field, when the page carries a header. */
    val uptimeSeconds: Int?,
    /** The `/N` header field: the RTC weekday, Monday as 1. Not a page number. */
    val headerWeekday: Int?,
    /** Bytes between the delimiters, header included. Fixed per page, so a short record lost bytes. */
    val byteLength: Int,
    /**
     * True when this record cannot be trusted field by field.
     *
     * Set when the record is shorter than an intact record of the same page, or when a name repeats
     * because a delimiter was destroyed and two records merged. Both happen when the firmware ring
     * overflows, which it does whenever the binary co-stream runs alongside the text: measured on
     * `20260811_124239`, 69 of 71 records were damaged. With the co-stream off the same wheel
     * delivered 70 of 70 intact, so a consumer should treat a suspect record as a gap rather than
     * try to salvage numbers from it.
     *
     * Individual values cannot be validated on their own. A record cut mid-number splices the tail
     * of one field onto the head of the next and produces readings that are the right width and the
     * wrong value - `363272>FdHz` and `140465>Out` are both from that dump.
     */
    val suspect: Boolean,
)

/**
 * Streaming parser for the LeaperKim `StR` diagnostic text.
 *
 * Feed it every notify payload. It strips the 70-byte `StR` packet framing when present, so the same
 * parser handles both transports the wheel can print in: raw characters at mode byte <= 2 and framed
 * packets at >= 3. A packet header can land anywhere, including the middle of a number, and removing
 * it restores the record exactly.
 */
class LeaperKimStrRecordParser(
    private val maxBufferBytes: Int = DefaultMaxBufferBytes,
) {
    private class Mark(val offset: Int, val timestampMillis: Long)

    /**
     * A page, identified by the names it prints. Lengths are collected rather than fixed: padding
     * shifts by a character when a value fills its `printf` width, so one page legitimately has
     * more than one length while its field list stays identical.
     */
    private class Page(val names: List<String>) {
        val byteLengths = mutableSetOf<Int>()
        var sightings: Int = 0
    }

    private var buffer = ByteArray(0)
    private val marks = mutableListOf<Mark>()
    private val pages = mutableListOf<Page>()

    /** Bytes of a partly-received packet header held back until the rest of it arrives. */
    private var headerCarry = 0

    fun reset() {
        buffer = ByteArray(0)
        marks.clear()
        headerCarry = 0
        // Learned page shapes are kept: they describe the firmware, not the connection.
    }

    fun ingest(bytes: ByteArray, timestampMillis: Long): List<LeaperKimStrRecord> {
        if (bytes.isEmpty()) return emptyList()
        append(stripPacketHeaders(bytes), timestampMillis)
        return drainRecords()
    }

    /**
     * Removes `53 74 52 7F 00 40` wherever it appears, carrying a split header across calls so a
     * header divided by a notification boundary is still removed rather than left in the text.
     */
    private fun stripPacketHeaders(bytes: ByteArray): ByteArray {
        val header = LeaperKimStrStreamDetector.StrPacketHeader
        val out = ByteArray(bytes.size + headerCarry)
        var write = 0
        var read = 0
        // Re-offer the carried prefix so a header straddling the boundary is matched as a whole.
        if (headerCarry > 0) {
            var matched = headerCarry
            while (matched > 0) {
                if (continuesHeader(bytes, matched)) break
                matched--
            }
            if (matched == headerCarry && headerCarry + bytes.size >= header.size) {
                read = header.size - headerCarry
                headerCarry = 0
                if (read > bytes.size) read = bytes.size
            } else {
                for (index in 0 until headerCarry) out[write++] = header[index]
                headerCarry = 0
            }
        }
        while (read < bytes.size) {
            val remaining = bytes.size - read
            if (remaining < header.size && isHeaderPrefix(bytes, read, remaining)) {
                headerCarry = remaining
                read = bytes.size
                break
            }
            if (matchesAt(bytes, read, header)) {
                read += header.size
                continue
            }
            out[write++] = bytes[read++]
        }
        return out.copyOf(write)
    }

    private fun continuesHeader(bytes: ByteArray, carried: Int): Boolean {
        val header = LeaperKimStrStreamDetector.StrPacketHeader
        var index = 0
        while (index < header.size - carried && index < bytes.size) {
            if (bytes[index] != header[carried + index]) return false
            index++
        }
        return true
    }

    private fun isHeaderPrefix(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val header = LeaperKimStrStreamDetector.StrPacketHeader
        for (index in 0 until length) {
            if (bytes[offset + index] != header[index]) return false
        }
        return true
    }

    private fun append(bytes: ByteArray, timestampMillis: Long) {
        if (bytes.isEmpty()) return
        marks += Mark(buffer.size, timestampMillis)
        buffer = buffer + bytes
        if (buffer.size > maxBufferBytes) {
            // Keep the tail: an oversized buffer means the delimiter never came, so the head is a
            // record we will never be able to close.
            val drop = buffer.size - maxBufferBytes
            buffer = buffer.copyOfRange(drop, buffer.size)
            shiftMarks(drop)
        }
    }

    private fun shiftMarks(drop: Int) {
        val kept = mutableListOf<Mark>()
        for (mark in marks) {
            val moved = mark.offset - drop
            if (moved >= 0) kept += Mark(moved, mark.timestampMillis)
            else if (kept.isEmpty()) kept += Mark(0, mark.timestampMillis)
        }
        marks.clear()
        marks += kept
    }

    private fun timestampAt(offset: Int): Long {
        var stamp = marks.firstOrNull()?.timestampMillis ?: 0L
        for (mark in marks) {
            if (mark.offset > offset) break
            stamp = mark.timestampMillis
        }
        return stamp
    }

    private fun drainRecords(): List<LeaperKimStrRecord> {
        val out = mutableListOf<LeaperKimStrRecord>()
        while (true) {
            val first = indexOfDelimiter(0) ?: break
            val second = indexOfDelimiter(first + RecordDelimiter.size) ?: break
            val body = buffer.copyOfRange(first + RecordDelimiter.size, second)
            out += parseRecord(body, timestampAt(second))
            val drop = second
            buffer = buffer.copyOfRange(drop, buffer.size)
            shiftMarks(drop)
        }
        return out
    }

    private fun indexOfDelimiter(from: Int): Int? {
        var offset = from
        while (offset + RecordDelimiter.size <= buffer.size) {
            if (matchesAt(buffer, offset, RecordDelimiter)) return offset
            offset++
        }
        return null
    }

    private fun parseRecord(body: ByteArray, timestampMillis: Long): LeaperKimStrRecord {
        val fields = LinkedHashMap<String, Double>()
        val order = mutableListOf<String>()
        var offset = 0
        var previousTokenEnd = -1
        while (offset < body.size) {
            // A field that begins exactly where the previous one ended needs no separator: that is
            // the printf-overflow case, and the boundary itself is the separator.
            val token = readField(body, offset, atTokenBoundary = offset == previousTokenEnd)
            if (token == null) {
                offset++
                continue
            }
            previousTokenEnd = token.end
            // Every occurrence goes into the shape, repeats included: the IMU page really does print
            // `GZ` twice (` 8>GZ     0>GZ`), so a repeated name is not evidence of anything. A
            // record merged by a lost delimiter shows up instead as the page's whole sequence twice
            // over, which is simply a different shape and stays unconfirmed.
            order += token.name
            fields[token.name] = token.value
            offset = token.end
        }
        val length = body.size + RecordDelimiter.size
        val suspect = !confirmShape(order, length)
        return LeaperKimStrRecord(
            timestampMillis = timestampMillis,
            fields = fields,
            uptimeSeconds = readUptime(body),
            headerWeekday = readWeekday(body),
            byteLength = length,
            suspect = suspect,
        )
    }

    /**
     * True once this exact shape - same field names in the same order, same byte length - has been
     * seen at least twice.
     *
     * A single record carries nothing that says whether it is whole. Every page is a fixed-width
     * `printf`, so an intact record of a given page always has the same length, but the first record
     * of a page could itself be the damaged one, and registering its shape would then bless every
     * later copy of the same damage. Requiring a second identical sighting costs 500 ms at the start
     * of each page and removes that whole class of error.
     */
    private fun confirmShape(names: List<String>, byteLength: Int): Boolean {
        val page = pages.firstOrNull { it.names == names }
            ?: Page(names).also { pages += it }
        val knownLength = byteLength in page.byteLengths
        page.byteLengths += byteLength
        page.sightings++
        // A length never seen with these names is unproven on its own showing, even on a page that
        // is otherwise confirmed: that is the shape a record which lost bytes from inside a value
        // would take, keeping every name intact.
        return knownLength && page.sightings >= ConfirmingSightings
    }

    private class Field(val name: String, val value: Double, val end: Int)

    /**
     * Reads one `<number>><Name>` or `<number><Name>` token at [offset].
     *
     * The number must be preceded by a space or a line break. The firmware formats are padded
     * (`%6d>IM`, `%5d>CdA`), so a number that lost its leading spaces to a cut is the one case where
     * the separator is missing, and refusing it there is what keeps a spliced value out.
     */
    private fun readField(body: ByteArray, offset: Int, atTokenBoundary: Boolean = false): Field? {
        if (offset > 0 && !atTokenBoundary) {
            val before = body[offset - 1]
            if (before != ' '.code.toByte() && before != '\n'.code.toByte() &&
                before != '\r'.code.toByte()
            ) {
                return null
            }
        }
        var at = offset
        val start = at
        if (at < body.size && body[at] == '-'.code.toByte()) at++
        var digits = 0
        while (at < body.size && isDigit(body[at])) { at++; digits++ }
        if (digits == 0) return null
        if (at < body.size && body[at] == '.'.code.toByte()) {
            at++
            var decimals = 0
            while (at < body.size && isDigit(body[at])) { at++; decimals++ }
            if (decimals == 0) return null
        }
        val numberEnd = at
        if (at < body.size && body[at] == '>'.code.toByte()) at++
        val nameStart = at
        // A name may start with a digit - `6Dpp` is a real field - so the rule is "contains a
        // letter", not "starts with one".
        while (at < body.size && (isLetter(body[at]) || isDigit(body[at]))) at++
        var splitFromOverflow = false
        if (at < body.size && body[at] == '>'.code.toByte()) {
            // The wheel prints `%10d>AD`, and a value that fills all ten digits eats its own
            // padding: `0>isH1017140807>DS`, `1275>CpS1513631385>AD`. No byte is lost - the record
            // is still its usual length - but the separator is gone, so the trailing digit run
            // belongs to the next field's value, not to this name. Real records from dumps
            // 20260811_141039 and 20260811_141529.
            while (at > nameStart && isDigit(body[at - 1])) {
                at--
                splitFromOverflow = true
            }
        }
        val nameLength = at - nameStart
        if (nameLength == 0 || nameLength > MaxFieldNameLength) return null
        var hasLetter = false
        for (index in nameStart until at) {
            if (isLetter(body[index])) { hasLetter = true; break }
        }
        if (!hasLetter) return null
        // A name must end the token: anything else means this is not a field. Two exceptions, both
        // the same printf overflow. Above, a value that filled its width with digits abutted the
        // name. Here, a negative value did it with its minus sign: `49006>CpS-532655874>AD`, where
        // ten characters of `%10d` leave no padding. A minus can only begin the next value, since
        // no field name contains one.
        if (at < body.size && !splitFromOverflow) {
            val after = body[at]
            val startsNegativeValue = after == '-'.code.toByte() &&
                at + 1 < body.size && isDigit(body[at + 1])
            if (!startsNegativeValue &&
                after != ' '.code.toByte() && after != '\n'.code.toByte() &&
                after != '\r'.code.toByte()
            ) {
                return null
            }
        }
        val value = decodeAscii(body, start, numberEnd).toDoubleOrNull() ?: return null
        return Field(decodeAscii(body, nameStart, at), value, at)
    }

    /** `H:MM:SS` in the header, present only on pages that print one. */
    private fun readUptime(body: ByteArray): Int? {
        var offset = 0
        while (offset < body.size) {
            if (body[offset] == ':'.code.toByte()) {
                val parsed = readUptimeAt(body, offset)
                if (parsed != null) return parsed
            }
            offset++
        }
        return null
    }

    private fun readUptimeAt(body: ByteArray, colon: Int): Int? {
        var start = colon
        var digits = 0
        while (start > 0 && isDigit(body[start - 1])) { start--; digits++ }
        if (digits == 0) return null
        // The date carries colons too; the uptime is the one preceded by a space.
        if (start > 0 && body[start - 1] != ' '.code.toByte()) return null
        val hours = decodeAscii(body, start, colon).toIntOrNull() ?: return null
        var at = colon + 1
        val minuteStart = at
        while (at < body.size && isDigit(body[at])) at++
        if (at - minuteStart != 2 || at >= body.size || body[at] != ':'.code.toByte()) return null
        val minutes = decodeAscii(body, minuteStart, at).toIntOrNull() ?: return null
        at++
        val secondStart = at
        while (at < body.size && isDigit(body[at])) at++
        if (at - secondStart != 2) return null
        // The clock in the header has the same `H:MM:SS` shape and is also preceded by a space, so
        // the space alone does not separate them: `2026-08-11 17:52:28/2  0:00:30` would otherwise
        // read as 64348 seconds of uptime. The date's time is followed by `/`, the uptime by a space.
        if (at < body.size && body[at] != ' '.code.toByte()) return null
        val seconds = decodeAscii(body, secondStart, at).toIntOrNull() ?: return null
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun readWeekday(body: ByteArray): Int? {
        var offset = 0
        while (offset < body.size) {
            if (body[offset] == '/'.code.toByte() && offset + 1 < body.size &&
                isDigit(body[offset + 1])
            ) {
                var at = offset + 1
                while (at < body.size && isDigit(body[at])) at++
                if (at - offset - 1 <= 2) {
                    return decodeAscii(body, offset + 1, at).toIntOrNull()
                }
            }
            offset++
        }
        return null
    }

    companion object {
        const val DefaultMaxBufferBytes: Int = 8_192

        /** How many identical sightings confirm a page shape. See [LeaperKimStrRecord.suspect]. */
        const val ConfirmingSightings: Int = 2

        /** Every page is delimited by this, whether or not a dated header follows it. */
        val RecordDelimiter: ByteArray = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

        /** Longest field name seen across all seven pages is `NxtRdy`. */
        const val MaxFieldNameLength: Int = 6
    }
}

private fun isDigit(byte: Byte): Boolean = byte >= '0'.code.toByte() && byte <= '9'.code.toByte()

private fun isLetter(byte: Byte): Boolean =
    (byte >= 'A'.code.toByte() && byte <= 'Z'.code.toByte()) ||
        (byte >= 'a'.code.toByte() && byte <= 'z'.code.toByte())

private fun decodeAscii(bytes: ByteArray, from: Int, to: Int): String {
    val chars = CharArray(to - from)
    for (index in chars.indices) chars[index] = (bytes[from + index].toInt() and 0xFF).toChar()
    return chars.concatToString()
}

private fun matchesAt(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
    if (offset < 0 || offset + pattern.size > bytes.size) return false
    for (index in pattern.indices) {
        if (bytes[offset + index] != pattern[index]) return false
    }
    return true
}
