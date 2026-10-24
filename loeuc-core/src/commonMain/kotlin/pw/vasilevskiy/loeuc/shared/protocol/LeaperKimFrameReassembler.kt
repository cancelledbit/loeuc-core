package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Splits a LeaperKim byte stream into whole `DC 5A 5C` frames.
 *
 * Notifications carry 20 bytes each and a frame spans several of them, so every consumer of this
 * protocol needs the same reassembly: [LeaperKimProtocolEngine] to decode telemetry, and
 * [LeaperKimLogSession] to pick the event-log pages out of the same stream. It lives apart from
 * the engine so those two cannot drift: a frame boundary is a property of the wire, not of what
 * the caller does with the bytes.
 *
 * The length byte at offset `3` counts the payload after the four-byte header, which is why the
 * whole frame is `u8(3) + 4` bytes. CRC32 is not checked here - a page-33 log record is read out
 * of the frame as it arrived, exactly as the engine reads a legacy frame that carries no CRC at
 * all; the engine checks the trailer itself for the pages it decodes.
 */
class LeaperKimFrameReassembler {

    private var buffer = byteArrayOf()

    /** Drops whatever is half-assembled. Called when the link goes away. */
    fun reset() {
        buffer = byteArrayOf()
    }

    /**
     * The whole frames [chunk] completes, in arrival order. A partial tail stays buffered for the
     * next call.
     */
    fun consume(chunk: ByteArray): List<ByteArray> {
        buffer += chunk
        val frames = ArrayList<ByteArray>()
        while (true) {
            val markerIndex = buffer.indexOfLeaperKimMarker()
            if (markerIndex < 0) {
                // Two bytes survive: a marker split across a notification boundary would otherwise
                // be thrown away together with the noise in front of it.
                buffer = buffer.takeLast(2).toByteArray()
                return frames
            }
            if (markerIndex > 0) {
                buffer = buffer.copyOfRange(markerIndex, buffer.size)
            }
            if (buffer.size < 4) {
                return frames
            }
            val frameLength = (buffer[3].toInt() and 0xFF) + 4
            // Legacy frames have no CRC. A lost notification must not make the next header
            // become the previous frame's PWM/pose, or consume the start of the next frame.
            if (frameLength == 36) {
                val nested = buffer.copyOfRange(4, buffer.size).indexOfLeaperKimMarker()
                    .takeIf { it >= 0 }?.plus(4)
                if (nested != null && nested < frameLength &&
                    buffer.getOrNull(nested + 3) == 0x20.toByte()
                ) {
                    buffer = buffer.copyOfRange(nested, buffer.size)
                    continue
                }
            }
            if (buffer.size < frameLength) {
                return frames
            }
            frames.add(buffer.copyOfRange(0, frameLength))
            buffer = buffer.copyOfRange(frameLength, buffer.size)
        }
    }
}

internal fun ByteArray.indexOfLeaperKimMarker(): Int {
    for (index in 0..size - 3) {
        if (this[index] == 0xDC.toByte() &&
            this[index + 1] == 0x5A.toByte() &&
            this[index + 2] == 0x5C.toByte()
        ) {
            return index
        }
    }
    return -1
}
