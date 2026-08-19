package pw.vasilevskiy.loeuc.shared.protocol

/** One unsolicited telemetry line emitted by the original Inventist Solowheel Xtreme. */
data class SolowheelXtremeTelemetry(
    val motionRaw: Int,
    val speedKmh: Double,
    val voltage: Double,
    val stateRaw: Int,
)

data class SolowheelXtremeReassemblyResult(
    val frames: List<ByteArray>,
    val remainingBuffer: ByteArray,
)

/**
 * Decoder for the original (pre-Inmotion) Solowheel Xtreme ASCII protocol.
 *
 * The wheel publishes ` %05d, %05d, %05d,\r\n` on FFF7 about once per second.
 * Voltage is confirmed by a real wheel capture (553/554 at rest = 55.3/55.4 V).
 * Speed scale is calibrated from a real ride: the wheel raised its pedals at
 * 18 km/h while the raw field was about 675, giving raw / 37.5 km/h.
 * The meaning of the state field is unknown, so it intentionally remains raw.
 */
class SolowheelXtremeProtocolEngine {
    private var buffer = byteArrayOf()

    fun reset() {
        buffer = byteArrayOf()
    }

    fun consume(chunk: ByteArray): SolowheelXtremeTelemetry? {
        buffer += chunk
        val result = reassembleSolowheelXtremeFrames(buffer)
        buffer = result.remainingBuffer
        return result.frames.asReversed().firstNotNullOfOrNull(::decodeSolowheelXtremeFrame)
    }
}

fun reassembleSolowheelXtremeFrames(buffer: ByteArray): SolowheelXtremeReassemblyResult {
    val frames = mutableListOf<ByteArray>()
    var start = 0
    var index = 0
    while (index + 1 < buffer.size) {
        if (buffer[index] == '\r'.code.toByte() && buffer[index + 1] == '\n'.code.toByte()) {
            // A real riding capture contained a short 0xFF noise burst immediately
            // before one otherwise valid line. Since the record size is fixed, parse
            // the 23-byte tail ending at CRLF and discard any prefix noise.
            val candidateStart = maxOf(start, index + 2 - XTREME_FRAME_SIZE)
            val candidate = buffer.copyOfRange(candidateStart, index + 2)
            if (decodeSolowheelXtremeFrame(candidate) != null) {
                frames += candidate
            }
            start = index + 2
            index = start
        } else {
            index += 1
        }
    }
    return SolowheelXtremeReassemblyResult(
        frames = frames,
        remainingBuffer = buffer.copyOfRange(start, buffer.size),
    )
}

fun decodeSolowheelXtremeFrame(frame: ByteArray): SolowheelXtremeTelemetry? {
    if (frame.size != XTREME_FRAME_SIZE || frame[21] != '\r'.code.toByte() || frame[22] != '\n'.code.toByte()) {
        return null
    }
    val text = frame.decodeToString(0, 21)
    val match = XTREME_LINE.matchEntire(text) ?: return null
    return SolowheelXtremeTelemetry(
        motionRaw = match.groupValues[1].toIntOrNull() ?: return null,
        speedKmh = (match.groupValues[1].toIntOrNull() ?: return null) * XTREME_SPEED_FACTOR,
        voltage = (match.groupValues[2].toIntOrNull() ?: return null) / 10.0,
        stateRaw = match.groupValues[3].toIntOrNull() ?: return null,
    )
}

private val XTREME_LINE = Regex("""\s(-?\d{5}),\s(-?\d{5}),\s(-?\d{5}),""")
private const val XTREME_FRAME_SIZE = 23
private const val XTREME_SPEED_FACTOR = 1.0 / 37.5
