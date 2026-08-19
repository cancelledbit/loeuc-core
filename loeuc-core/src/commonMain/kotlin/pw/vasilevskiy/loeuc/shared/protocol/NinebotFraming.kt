package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Wire format of the Ninebot Z series (Z6/Z8/Z10).
 *
 * ```
 * 5A A5 | len | src | dst | cmd | param | data[len] | crc_lo crc_hi
 * ```
 *
 * A frame is `len + 9` bytes. The checksum covers `len` through the last data byte. Everything
 * from `src` onward is XORed with a 16-byte keystream; see [NinebotKeystream].
 *
 * Nothing here is device state, so it is an object: the engine owns the stream, this owns the
 * bytes.
 */
object NinebotFrameCodec {
    const val MarkerFirst: Byte = 0x5A
    const val MarkerSecond: Byte = 0xA5.toByte()

    /** Bytes a frame carries beyond its data: two markers, five header bytes, two checksum. */
    const val FrameOverhead: Int = 9

    /** Smallest prefix that reveals how long the frame will be. */
    private const val LengthPrefixSize: Int = 3

    fun build(
        destination: Int,
        command: Int,
        param: Int,
        data: ByteArray,
        source: Int = NinebotAddress.App,
        keystream: NinebotKeystream = NinebotKeystream.Identity,
    ): ByteArray {
        val body = byteArrayOf(
            data.size.toByte(),
            source.toByte(),
            destination.toByte(),
            command.toByte(),
            param.toByte(),
        ) + data
        val checksum = checksum(body)
        val withChecksum = body + byteArrayOf(
            (checksum and 0xFF).toByte(),
            ((checksum shr 8) and 0xFF).toByte(),
        )
        return byteArrayOf(MarkerFirst, MarkerSecond) + keystream.apply(withChecksum)
    }

    /**
     * The wheel's checksum: an unsigned byte sum, inverted.
     *
     * Inverting means a run of zero bytes does not checksum to zero, which is what lets a
     * garbage frame of zeros be rejected rather than accepted as valid-and-empty.
     */
    fun checksum(body: ByteArray): Int {
        var sum = 0
        for (byte in body) {
            sum += byte.toInt() and 0xFF
        }
        return (sum xor 0xFFFF) and 0xFFFF
    }

    /**
     * Pulls every complete, checksum-clean frame out of [buffer].
     *
     * Returns the frames and whatever tail could not be parsed yet, so the caller can hold it
     * until the next BLE notification arrives. A frame whose checksum fails is dropped and the
     * scan resumes one byte past its marker - a corrupt length byte would otherwise swallow the
     * frames behind it.
     */
    fun extract(
        buffer: ByteArray,
        keystream: NinebotKeystream = NinebotKeystream.Identity,
    ): NinebotExtractionResult {
        val frames = mutableListOf<NinebotFrame>()
        var rest = buffer

        while (true) {
            val markerIndex = rest.indexOfMarker()
            if (markerIndex < 0) {
                // A lone trailing 0x5A may be the first half of the next frame's marker.
                val tail = if (rest.isNotEmpty() && rest.last() == MarkerFirst) {
                    byteArrayOf(MarkerFirst)
                } else {
                    ByteArray(0)
                }
                return NinebotExtractionResult(frames, tail)
            }
            if (markerIndex > 0) {
                rest = rest.copyOfRange(markerIndex, rest.size)
            }
            if (rest.size < LengthPrefixSize) {
                return NinebotExtractionResult(frames, rest)
            }

            val length = rest[2].toInt() and 0xFF
            val frameSize = length + FrameOverhead
            if (rest.size < frameSize) {
                return NinebotExtractionResult(frames, rest)
            }

            val frame = parse(rest.copyOfRange(0, frameSize), keystream)
            if (frame == null) {
                rest = rest.copyOfRange(1, rest.size)
            } else {
                frames += frame
                rest = rest.copyOfRange(frameSize, rest.size)
            }
        }
    }

    /** Decrypts and validates one complete frame. Null when the checksum does not hold. */
    fun parse(
        frame: ByteArray,
        keystream: NinebotKeystream = NinebotKeystream.Identity,
    ): NinebotFrame? {
        if (frame.size < FrameOverhead) return null
        if (frame[0] != MarkerFirst || frame[1] != MarkerSecond) return null

        val body = keystream.apply(frame.copyOfRange(2, frame.size))
        val length = body[0].toInt() and 0xFF
        if (body.size != length + FrameOverhead - 2) return null

        val checksumOffset = body.size - 2
        val declared = (body[checksumOffset].toInt() and 0xFF) or
            ((body[checksumOffset + 1].toInt() and 0xFF) shl 8)
        if (declared != checksum(body.copyOfRange(0, checksumOffset))) return null

        return NinebotFrame(
            source = body[1].toInt() and 0xFF,
            destination = body[2].toInt() and 0xFF,
            command = body[3].toInt() and 0xFF,
            param = body[4].toInt() and 0xFF,
            payload = body.copyOfRange(5, checksumOffset),
        )
    }

    private fun ByteArray.indexOfMarker(): Int {
        for (index in 0 until size - 1) {
            if (this[index] == MarkerFirst && this[index + 1] == MarkerSecond) return index
        }
        return -1
    }
}

/**
 * The XOR keystream the wheel applies from `src` onward.
 *
 * The Z-series handshake never asks for one: after the BLE-version answer the sequence goes
 * straight to reading the serial number, so the keystream stays [Identity] and the transform is
 * a no-op. It exists as a type rather than as an `if` so that a Ninebot model that *does*
 * negotiate a key can be added without reopening the framing.
 */
class NinebotKeystream(private val gamma: ByteArray) {
    init {
        require(gamma.isEmpty() || gamma.size == KeyLength) {
            "Ninebot keystream must be empty or $KeyLength bytes, was ${gamma.size}"
        }
    }

    val isIdentity: Boolean get() = gamma.isEmpty() || gamma.all { it == 0.toByte() }

    /**
     * XORs in place from index 1 - the length byte travels in the clear, which is what lets a
     * reader find frame boundaries before it knows the key.
     */
    fun apply(body: ByteArray): ByteArray {
        if (isIdentity) return body.copyOf()
        val result = body.copyOf()
        for (index in 1 until result.size) {
            result[index] = (result[index].toInt() xor gamma[(index - 1) % KeyLength].toInt()).toByte()
        }
        return result
    }

    companion object {
        const val KeyLength: Int = 16
        val Identity: NinebotKeystream = NinebotKeystream(ByteArray(0))
    }
}

data class NinebotFrame(
    val source: Int,
    val destination: Int,
    val command: Int,
    val param: Int,
    val payload: ByteArray,
) {
    val isFromController: Boolean get() = source == NinebotAddress.Controller

    /** 1 or 2 for a BMS answer, null for anything else. */
    val bmsIndex: Int?
        get() = when (source) {
            NinebotAddress.Bms1 -> 1
            NinebotAddress.Bms2 -> 2
            else -> null
        }

    override fun equals(other: Any?): Boolean {
        return other is NinebotFrame &&
            source == other.source &&
            destination == other.destination &&
            command == other.command &&
            param == other.param &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = source
        result = 31 * result + destination
        result = 31 * result + command
        result = 31 * result + param
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class NinebotExtractionResult(
    val frames: List<NinebotFrame>,
    val remainingBuffer: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        return other is NinebotExtractionResult &&
            frames == other.frames &&
            remainingBuffer.contentEquals(other.remainingBuffer)
    }

    override fun hashCode(): Int {
        var result = frames.hashCode()
        result = 31 * result + remainingBuffer.contentHashCode()
        return result
    }
}

/**
 * A settings answer, read as what it is: an array of 16-bit slots addressed by parameter id.
 *
 * The wheel numbers its parameters one id per 16-bit slot, so the id that names a write also
 * locates the value inside a read:
 *
 * ```
 * offset = (paramId - baseParamId) * 2
 * ```
 *
 * That rule holds for every field of all three settings blocks - `0xC6` LED mode at 0, `0xD2`
 * pedal sensitivity at 24, `0xD3` drive flags at 26, `0x7C` alarm mask at 24, and so on. Reading
 * the block this way, instead of through a table of offsets, is what lets the settings catalog
 * be a list of parameter ids rather than a list of magic numbers.
 */
class NinebotParamBlock(
    val baseParamId: Int,
    val payload: ByteArray,
) {
    /** Byte offset of [paramId] within the block, or null if the block is too short for it. */
    fun offsetOf(paramId: Int): Int? {
        if (paramId < baseParamId) return null
        val offset = (paramId - baseParamId) * 2
        return if (offset + 1 < payload.size) offset else null
    }

    /** The 16-bit slot [paramId] names, unsigned. */
    fun value(paramId: Int): Int? {
        val offset = offsetOf(paramId) ?: return null
        return (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** One bit of the slot [paramId] names. */
    fun bit(paramId: Int, bit: Int): Int? {
        val value = value(paramId) ?: return null
        return (value shr bit) and 1
    }

    /** The byte at [byteOffset] past the start of [paramId]'s slot pair. */
    fun byte(paramId: Int, byteOffset: Int): Int? {
        val offset = offsetOf(paramId) ?: return null
        val index = offset + byteOffset
        if (index !in payload.indices) return null
        return payload[index].toInt() and 0xFF
    }
}

object NinebotAddress {
    const val Bms1: Int = 0x11
    const val Bms2: Int = 0x12
    const val Controller: Int = 0x14
    const val KeyGenerator: Int = 0x16
    const val App: Int = 0x3E
}

object NinebotCommand {
    const val Read: Int = 0x01
    const val Write: Int = 0x03
    const val GetKey: Int = 0x5B
}

/**
 * Parameter ids. The three settings blocks are read by asking their *base* id for enough bytes
 * to cover every field above it, which is why the base ids double as block identifiers.
 */
object NinebotParam {
    const val Key: Int = 0x00
    const val SerialNumber: Int = 0x10
    const val Firmware: Int = 0x1A
    const val BleVersion: Int = 0x68
    const val ActivationDate: Int = 0x69

    // --- settings block 1, base 0x70
    const val LockMode: Int = 0x70
    const val LimitedMode: Int = 0x72
    const val LimitedSpeedFirstKm: Int = 0x73
    const val LimitedSpeed: Int = 0x74
    const val Calibration: Int = 0x75
    const val Alarms: Int = 0x7C
    const val Alarm1Speed: Int = 0x7D
    const val Alarm2Speed: Int = 0x7E
    const val Alarm3Speed: Int = 0x7F

    const val LiveData: Int = 0xB0

    // --- settings block 2, base 0xC6
    const val LedMode: Int = 0xC6
    const val LedColor1: Int = 0xC8
    const val LedColor2: Int = 0xCA
    const val LedColor3: Int = 0xCC
    const val LedColor4: Int = 0xCE
    const val PedalSensitivity: Int = 0xD2
    const val DriveFlags: Int = 0xD3

    // --- settings block 3, base 0xF5
    const val SpeakerVolume: Int = 0xF5

    // --- BMS
    const val BmsSerial: Int = 0x10
    const val BmsLife: Int = 0x30
    const val BmsCells: Int = 0x40
}

/** Bit positions inside the [NinebotParam.DriveFlags] word. */
object NinebotDriveFlag {
    const val DaytimeRunningLight: Int = 0
    const val TailLight: Int = 1
    const val Headlight: Int = 2
    const val HandleButton: Int = 3
    const val BrakeAssist: Int = 4
}
