package pw.vasilevskiy.loeuc.shared.protocol


/**
 * Field names are the vendor app's own, taken from the labels its telemetry page binds each
 * `VALUES` slot to - see the table in `docs/hw-charger-protocol-notes.md`. `input*` is the mains
 * side, `output*` the battery side; the charger reports no separate "set vs measured" pair.
 */
data class HwChargerTelemetry(
    val inputVoltage: Double = Double.NaN,
    val inputCurrent: Double = Double.NaN,
    val inputFrequency: Double = Double.NaN,
    val temperature: Double = Double.NaN,
    val temperature1: Double = Double.NaN,
    val outputVoltage: Double = Double.NaN,
    val outputCurrent: Double = Double.NaN,
    val currentPoint: Double = Double.NaN,
    val efficiency: Double = Double.NaN,
    /** The charger's own output switch: 1 while it is delivering, 0 while it is closed. */
    val outputState: Int = -1,
    val ah: Double = Double.NaN,
    val wh: Double = Double.NaN,
    val moduleNumber: Int = -1,
    val rawFrame: ByteArray? = null,
) {
    /** Both power readings are the vendor page's own products; neither is on the wire. */
    val inputPower: Double get() = inputVoltage * inputCurrent
    val outputPower: Double get() = outputVoltage * outputCurrent

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HwChargerTelemetry) return false
        if (inputVoltage != other.inputVoltage) return false
        if (inputCurrent != other.inputCurrent) return false
        if (inputFrequency != other.inputFrequency) return false
        if (temperature != other.temperature) return false
        if (temperature1 != other.temperature1) return false
        if (outputVoltage != other.outputVoltage) return false
        if (outputCurrent != other.outputCurrent) return false
        if (currentPoint != other.currentPoint) return false
        if (efficiency != other.efficiency) return false
        if (outputState != other.outputState) return false
        if (ah != other.ah) return false
        if (wh != other.wh) return false
        if (moduleNumber != other.moduleNumber) return false
        if (rawFrame != null) {
            if (other.rawFrame == null) return false
            if (!rawFrame.contentEquals(other.rawFrame)) return false
        } else if (other.rawFrame != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = inputVoltage.hashCode()
        result = 31 * result + inputCurrent.hashCode()
        result = 31 * result + inputFrequency.hashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + temperature1.hashCode()
        result = 31 * result + outputVoltage.hashCode()
        result = 31 * result + outputCurrent.hashCode()
        result = 31 * result + currentPoint.hashCode()
        result = 31 * result + efficiency.hashCode()
        result = 31 * result + outputState
        result = 31 * result + ah.hashCode()
        result = 31 * result + wh.hashCode()
        result = 31 * result + moduleNumber
        result = 31 * result + (rawFrame?.contentHashCode() ?: 0)
        return result
    }
}

data class HwChargerFrame(
    val command: Int,
    val payload: ByteArray,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HwChargerFrame) return false
        if (command != other.command) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class HwChargerReassemblyResult(
    val frames: List<HwChargerFrame>,
    val remainingBuffer: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HwChargerReassemblyResult) return false
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

enum class HwChargerPasswordVerdict {
    Accepted,
    Rejected,
}

class HwChargerProtocolEngine {
    private var buffer = byteArrayOf()
    private var state = HwChargerTelemetry()
    private var passwordVerdict: HwChargerPasswordVerdict? = null

    fun reset() {
        buffer = byteArrayOf()
        state = HwChargerTelemetry()
        passwordVerdict = null
    }

    fun telemetryPollingCommand(): ByteArray {
        return buildHwChargerCommand(HW_CHARGER_CMD_GET_VALUES)
    }

    /**
     * The charger's password, UTF-8 with the terminating NUL the vendor app appends. A charger
     * with no gate simply never answers, which is why nothing downstream may wait on this.
     */
    fun checkPasswordCommand(password: String): ByteArray {
        return buildHwChargerCommand(
            HW_CHARGER_CMD_CHECK_PASS,
            password.encodeToByteArray() + 0x00,
        )
    }

    /**
     * The verdict seen since the last call, if any. Reading clears it: a verdict answers one
     * handshake, and leaving it set would let a later connection read a stale answer.
     */
    fun takePasswordVerdict(): HwChargerPasswordVerdict? {
        val verdict = passwordVerdict
        passwordVerdict = null
        return verdict
    }

    fun consume(chunk: ByteArray): HwChargerTelemetry? {
        buffer += chunk
        val result = reassembleHwChargerFrames(buffer)
        buffer = result.remainingBuffer

        var updated = false
        for (frame in result.frames) {
            parseHwChargerPasswordAnswer(frame.bytes)?.let { passwordVerdict = it }
            if (processFrame(frame)) {
                updated = true
            }
        }

        return if (updated) state else null
    }

    fun consumeFrame(frame: ByteArray): HwChargerTelemetry? {
        if (!isValidHwChargerFrame(frame)) {
            return null
        }
        val command = frame[1].toInt() and 0xFF
        val payload = frame.copyOfRange(2, frame.size - 1)
        val hwFrame = HwChargerFrame(command = command, payload = payload, bytes = frame)
        return if (processFrame(hwFrame)) state else null
    }

    /**
     * The response's command byte is not checked. The vendor app reads the length, then that
     * many bytes, verifies the sum and hands everything after the command byte to
     * `VALUES.fromByteBloc` without ever looking at the command itself - so a decoder that
     * insisted on one particular value would reject frames the charger considers well formed.
     */
    private fun processFrame(frame: HwChargerFrame): Boolean {
        if (frame.payload.size >= HW_CHARGER_VALUES_PAYLOAD_SIZE) {
            val p = frame.payload
            state = HwChargerTelemetry(
                inputVoltage = p.floatLeAt(0).toDouble(),
                inputCurrent = p.floatLeAt(4).toDouble(),
                inputFrequency = p.floatLeAt(8).toDouble(),
                temperature = p.floatLeAt(12).toDouble(),
                temperature1 = p.floatLeAt(16).toDouble(),
                outputVoltage = p.floatLeAt(20).toDouble(),
                outputCurrent = p.floatLeAt(24).toDouble(),
                currentPoint = p.floatLeAt(28).toDouble(),
                efficiency = p.floatLeAt(32).toDouble(),
                outputState = p[36].toInt() and 0xFF,
                ah = p.floatLeAt(37).toDouble(),
                wh = p.floatLeAt(41).toDouble(),
                moduleNumber = p[45].toInt() and 0xFF,
                rawFrame = frame.bytes,
            )
            return true
        }
        return false
    }
}

/**
 * Mirrors `packetUtil::send` in the vendor app: a leading byte saying how many bytes follow,
 * the command, the payload, and an 8-bit additive sum of command and payload. The length byte
 * is deliberately outside the sum - the receiver has already consumed it by the time it starts
 * accumulating.
 */
fun buildHwChargerCommand(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
    val frame = ByteArray(payload.size + 3)
    frame[0] = (payload.size + 2).toByte()
    frame[1] = cmd.toByte()
    payload.copyInto(frame, destinationOffset = 2)
    var sum = cmd and 0xFF
    for (byte in payload) {
        sum += byte.toInt() and 0xFF
    }
    frame[frame.size - 1] = (sum and 0xFF).toByte()
    return frame
}

/**
 * Only the telemetry response is reassembled: a length byte of 48, then a command byte, 46
 * bytes of `VALUES` and the checksum. Everything else is skipped a byte at a time.
 *
 * Sync is thin - one known length byte and one checksum byte - so nothing wider is attempted.
 * An earlier version scanned every payload size 0..256 for a trailing byte that matched an
 * additive checksum, which guesses right by chance about once per 256 candidates; over ~60
 * candidate lengths it manufactured frames out of any byte stream, producing phantom frames on
 * 43 of the 83 LeaperKim/Begode/Inmotion dumps in this repo and taking over protocol
 * auto-detection on one of them. The other response lengths stay unguessed until a real charger
 * capture pins them down.
 */
fun reassembleHwChargerFrames(buffer: ByteArray): HwChargerReassemblyResult {
    val frames = mutableListOf<HwChargerFrame>()
    var offset = 0

    while (offset < buffer.size) {
        val frameSize = when (buffer[offset].toInt() and 0xFF) {
            HW_CHARGER_VALUES_LENGTH_BYTE -> HW_CHARGER_TELEMETRY_FRAME_SIZE
            HW_CHARGER_PASSWORD_LENGTH_BYTE -> HW_CHARGER_PASSWORD_FRAME_SIZE
            else -> {
                offset++
                continue
            }
        }
        if (buffer.size - offset < frameSize) {
            // Head of a frame that has not fully arrived yet - keep it buffered.
            break
        }
        val frameBytes = buffer.copyOfRange(offset, offset + frameSize)
        val command = frameBytes[1].toInt() and 0xFF
        val isKnownFrame = frameSize == HW_CHARGER_TELEMETRY_FRAME_SIZE ||
            command == HW_CHARGER_PASSWORD_RESPONSE_COMMAND
        if (!isKnownFrame || !isValidHwChargerFrame(frameBytes)) {
            offset++
            continue
        }
        frames += HwChargerFrame(
            command = command,
            payload = frameBytes.copyOfRange(2, frameSize - 1),
            bytes = frameBytes,
        )
        offset += frameSize
    }

    return HwChargerReassemblyResult(
        frames = frames,
        remainingBuffer = buffer.copyOfRange(offset, buffer.size),
    )
}

/**
 * The answer to `check_pass`, or null for anything else. The answer's own command byte is 0x04
 * rather than the 0x02 that was asked with, and the verdict is the first payload byte.
 */
fun parseHwChargerPasswordAnswer(frame: ByteArray): HwChargerPasswordVerdict? {
    if (frame.size != HW_CHARGER_PASSWORD_FRAME_SIZE) return null
    if ((frame[1].toInt() and 0xFF) != HW_CHARGER_PASSWORD_RESPONSE_COMMAND) return null
    if (!isValidHwChargerFrame(frame)) return null
    return if ((frame[2].toInt() and 0xFF) == HW_CHARGER_PASSWORD_ACCEPTED) {
        HwChargerPasswordVerdict.Accepted
    } else {
        HwChargerPasswordVerdict.Rejected
    }
}

private fun isValidHwChargerFrame(frame: ByteArray): Boolean {
    if (frame.size < 3) return false
    if ((frame[0].toInt() and 0xFF) != frame.size - 1) return false
    var sum = 0
    for (i in 1 until frame.size - 1) {
        sum += (frame[i].toInt() and 0xFF)
    }
    return (frame.last().toInt() and 0xFF) == (sum and 0xFF)
}

private fun ByteArray.floatLeAt(offset: Int): Float {
    val bits = (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits)
}

/**
 * The `hwcdq` module's triple, as its own pages set it before connecting: service FFE1, notify
 * FFE2, write FFE3. These are shifted one up from the usual HM-10 layout, and they are not
 * shared across the vendor app's five charger families - its `ev_test` module uses
 * FFE0/FFE1/FFE2 in the same three slots. LoEUC shipped that second triple by mistake, which is
 * why the first real charger subscribed correctly (the notify fallback found FFE2 anyway) and
 * then never received an answer: every poll went to a characteristic the charger does not read.
 */
const val HW_CHARGER_SERVICE_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
const val HW_CHARGER_NOTIFY_CHAR_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"
const val HW_CHARGER_WRITE_CHAR_UUID = "0000ffe3-0000-1000-8000-00805f9b34fb"

/**
 * Read off each method's own `send` call in the `hwcdq` module. An earlier version of this list
 * was invented and only happened to be right about `check_pass`, `get_sn` and `get_values`.
 *
 * `save_config`, `get_fw_ver` and `up_fw` are deliberately absent: each takes its command from a
 * field on the page at run time rather than a literal, so there is no constant to record.
 */
const val HW_CHARGER_CMD_CHECK_PASS = 0x02
const val HW_CHARGER_CMD_CHANGE_PASS = 0x03
const val HW_CHARGER_CMD_GET_SN = 0x04
const val HW_CHARGER_CMD_GET_CONFIG = 0x05
const val HW_CHARGER_CMD_GET_VALUES = 0x06

/** 9 floats, the output-state byte, two more floats and the module number, back to back. */
const val HW_CHARGER_VALUES_PAYLOAD_SIZE = 46

/** What the charger puts in the length byte of a `VALUES` answer: command + payload + checksum. */
const val HW_CHARGER_VALUES_LENGTH_BYTE = HW_CHARGER_VALUES_PAYLOAD_SIZE + 2

/**
 * The answer to `check_pass`: `04 04 <verdict> <?> <sum>`. Its command byte is 0x04, which is
 * not the 0x02 the request carried - the vendor app checks for exactly this pair.
 */
const val HW_CHARGER_PASSWORD_LENGTH_BYTE = 0x04
const val HW_CHARGER_PASSWORD_FRAME_SIZE = HW_CHARGER_PASSWORD_LENGTH_BYTE + 1
const val HW_CHARGER_PASSWORD_RESPONSE_COMMAND = 0x04
const val HW_CHARGER_PASSWORD_ACCEPTED = 0x02

/** The whole answer on the wire, length byte included. */
const val HW_CHARGER_TELEMETRY_FRAME_SIZE = HW_CHARGER_VALUES_LENGTH_BYTE + 1
