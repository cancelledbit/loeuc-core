package pw.vasilevskiy.loeuc.shared.protocol

/**
 * One trace bank: [LeaperKimCoStreamDecoder.RecordsPerPacket] consecutive samples of every field.
 *
 * The wheel samples at 500 Hz and ships forty samples per packet, so one packet is 80 ms of every
 * field at once. That is the whole point of this stream: the `StR` text prints the same RAM words
 * at 2 Hz.
 */
class LeaperKimCoStreamPacket internal constructor(
    val fieldCount: Int,
    private val values: IntArray,
    private val transmitted: BooleanArray,
) {
    val recordCount: Int get() = LeaperKimCoStreamDecoder.RecordsPerPacket

    /** The value of [field] at [record]. */
    fun value(record: Int, field: Int): Int = values[record * fieldCount + field]

    /**
     * True when this sample was actually carried, false when it was reconstructed from the
     * prediction.
     *
     * Worth keeping apart. A transmitted value is proven: the encoder clears the two low bits of
     * every sample to make room for the tag, so it arrives exactly. A reconstructed one is only as
     * good as the predictor, and the predictor is the part of the format still open - see the
     * class comment on [LeaperKimCoStreamDecoder].
     */
    fun isTransmitted(record: Int, field: Int): Boolean = transmitted[record * fieldCount + field]

    /** Every value of [field] across this packet, oldest first. */
    fun series(field: Int): IntArray = IntArray(recordCount) { value(it, field) }
}

/**
 * Streaming decoder for the LeaperKim `80 7F 7F 28` co-stream.
 *
 * Wire format and field table are in `docs/leaperkim-firmware-re-notes.md`, section
 * "The `80 7F 7F 28` Co-Stream: Complete Decode". Feed it every notify payload; it finds packet
 * headers itself, so the interleaved `StR` text between packets costs nothing.
 *
 * **What is proven and what is not.** Against capture `20260812_152341`, the 665 packets whose
 * header chain is provably intact all decode consuming exactly the declared payload length, and all
 * 65 193 transmitted values are multiples of four as the tag scheme requires - so the framing and
 * the values are established. The *predictor* is not. The firmware notes describe a second-order
 * predictor whose slope is recomputed from the last transmitted sample, and reconstructing that way
 * diverges wildly on real data (field 18 spans -13647..17398 against the text's 134..214), while
 * holding the slope at zero tracks the text exactly (0..220, same median of 192). The wheel was
 * parked for that capture, where the true slope is near zero and the two readings cannot be told
 * apart, so this decoder holds the slope at zero and marks every reconstructed sample through
 * [LeaperKimCoStreamPacket.isTransmitted]. A capture under motion is what settles it.
 */
class LeaperKimCoStreamDecoder {
    private var buffer = ByteArray(0)
    private var previous = IntArray(0)

    /** Packets that framed correctly but did not decode, so their contents were discarded. */
    var damagedPacketCount: Int = 0
        private set

    /** Packets handed out. */
    var packetCount: Int = 0
        private set

    fun reset() {
        buffer = ByteArray(0)
        previous = IntArray(0)
        damagedPacketCount = 0
        packetCount = 0
    }

    fun ingest(bytes: ByteArray): List<LeaperKimCoStreamPacket> {
        if (bytes.isEmpty()) return emptyList()
        buffer = buffer + bytes
        val out = mutableListOf<LeaperKimCoStreamPacket>()
        var read = 0
        while (true) {
            val start = indexOfHeader(read) ?: break
            if (start + HeaderSize > buffer.size) break
            val fields = buffer[start + 4].toInt() and 0xFF
            val declared = (buffer[start + 5].toInt() and 0xFF) or
                ((buffer[start + 6].toInt() and 0xFF) shl 8)
            val end = start + HeaderSize + declared
            if (end > buffer.size) break
            val packet = decode(fields, start + HeaderSize, declared)
            if (packet != null) {
                packetCount++
                out += packet
            } else {
                damagedPacketCount++
                // The prediction carries across packets, so a packet we could not read leaves every
                // field at an unknown value. Dropping the state makes the next packet's first
                // transmitted sample the point where each field becomes real again.
                previous = IntArray(0)
            }
            read = end
        }
        buffer = if (read == 0) trimmed(buffer) else buffer.copyOfRange(read, buffer.size)
        return out
    }

    /**
     * Decodes one payload, or returns null when it does not land exactly on [declared] bytes.
     *
     * That check is the whole safety net: the format carries no per-field length, so a payload that
     * lost bytes desynchronises and stops consuming its declared length almost immediately.
     */
    private fun decode(fields: Int, offset: Int, declared: Int): LeaperKimCoStreamPacket? {
        if (fields <= 0 || fields > MaxFields) return null
        if (previous.size != fields) previous = IntArray(fields)
        val values = IntArray(RecordsPerPacket * fields)
        val transmitted = BooleanArray(RecordsPerPacket * fields)
        val carried = previous.copyOf()
        var at = offset
        val limit = offset + declared
        for (field in 0 until fields) {
            if (at >= limit) return null
            val flags = buffer[at].toInt() and 0xFF
            at++
            // Bitmap bytes 0..5 are flagged by bits 5..0, in that order.
            val bitmap = IntArray(BitmapBytes)
            for (bit in 0 until BitmapBytes) {
                if (flags and (1 shl (BitmapBytes - 1 - bit)) != 0) {
                    if (at >= limit) return null
                    bitmap[bit] = buffer[at].toInt() and 0xFF
                    at++
                }
            }
            var value = carried[field]
            for (record in 0 until RecordsPerPacket) {
                val present = if (record == 0) {
                    flags and RecordZeroFlag != 0
                } else {
                    bitmap[record ushr 3] and (0x80 ushr (record and 7)) != 0
                }
                if (present) {
                    if (at >= limit) return null
                    val first = buffer[at].toInt() and 0xFF
                    val tag = first and 0x03
                    value = if (tag < 2) {
                        at++
                        // One byte: the high half comes from the prediction, which with a zero slope
                        // is the previous value.
                        toInt16((value and 0xFF00) or (first and 0xFC))
                    } else {
                        if (at + 1 >= limit) return null
                        val second = buffer[at + 1].toInt() and 0xFF
                        at += 2
                        toInt16((first and 0xFC) or (second shl 8))
                    }
                    transmitted[record * fields + field] = true
                }
                values[record * fields + field] = value
            }
            carried[field] = value
        }
        if (at != limit) return null
        previous = carried
        return LeaperKimCoStreamPacket(fields, values, transmitted)
    }

    private fun indexOfHeader(from: Int): Int? {
        var offset = from
        while (offset + Header.size <= buffer.size) {
            var matched = true
            for (index in Header.indices) {
                if (buffer[offset + index] != Header[index]) {
                    matched = false
                    break
                }
            }
            if (matched) return offset
            offset++
        }
        return null
    }

    /**
     * Keeps only the tail once nothing has been consumed for a while.
     *
     * The stream carries the `StR` text between packets, so bytes that are not part of a packet are
     * normal and must not accumulate. Three bytes are held back because a header can be split
     * across two notifications.
     */
    private fun trimmed(bytes: ByteArray): ByteArray {
        if (bytes.size <= MaxBufferBytes) return bytes
        return bytes.copyOfRange(bytes.size - Header.size + 1, bytes.size)
    }

    private fun toInt16(raw: Int): Int {
        val masked = raw and 0xFFFF
        return if (masked >= 0x8000) masked - 0x10000 else masked
    }

    companion object {
        /** `80 7F 7F 28`, written inline by the drain at `0x08011056`. */
        val Header: ByteArray = byteArrayOf(0x80.toByte(), 0x7F, 0x7F, 0x28)

        /** Header is the marker, the bank arming byte, and a little-endian payload length. */
        const val HeaderSize: Int = 7

        /** The bank commit flips banks on the fortieth record, so a packet is always forty. */
        const val RecordsPerPacket: Int = 40

        /** Six flag bits select six bitmap bytes, enough for records 1..47. */
        const val BitmapBytes: Int = 6

        /** Record 0 has no bitmap bit; it is flagged here instead. */
        const val RecordZeroFlag: Int = 0x40

        /** This writer ships 54 fields; the byte is read rather than assumed, so cap it sanely. */
        const val MaxFields: Int = 90

        /** A packet is a few hundred bytes, so anything past this is text that will never be one. */
        const val MaxBufferBytes: Int = 8_192
    }
}
