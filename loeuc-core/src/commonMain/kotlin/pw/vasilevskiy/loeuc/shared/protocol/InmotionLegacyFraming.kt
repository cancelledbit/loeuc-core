package pw.vasilevskiy.loeuc.shared.protocol

/*
 * Transport layer of the legacy InMotion "ezcan" protocol: a CAN message tunnelled over the
 * FFE0 serial-style BLE service. Spoken by the V5 / V8 / V10 families, Solowheel Glide 3 and
 * the older R-series, V3, L6 and Lively wheels. The newer V11+ wheels use a different protocol
 * that happens to share the AA AA header - see InmotionProtocolEngine.
 *
 * Wire frame:
 *
 *     AA AA | escaped( id u32 LE | data[8] | len | channel | format | type | extra[n] | check ) | 55 55
 *
 * - `len == 0xFE` marks an extended message: data[0..3] is the u32 LE length of `extra`.
 * - `check` is the low byte of the sum of every unescaped byte from id through extra.
 * - Escaping puts 0xA5 in front of every 0xAA, 0x55 and 0xA5 between the header and the tail,
 *   the check byte included. A real V8F capture ends `A5 55 55 55`: its check byte is 0x55 and
 *   the wheel escaped it.
 */

/** Notify service of legacy InMotion wheels (shared with several other brands). */
const val INMOTION_LEGACY_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"

/** Characteristic the wheel notifies on. */
const val INMOTION_LEGACY_NOTIFY_CHAR_UUID = "0000ffe4-0000-1000-8000-00805f9b34fb"

/** Service holding the write characteristic. */
const val INMOTION_LEGACY_WRITE_SERVICE_UUID = "0000ffe5-0000-1000-8000-00805f9b34fb"

/** Characteristic requests are written to, in chunks of at most 20 bytes, without response. */
const val INMOTION_LEGACY_WRITE_CHAR_UUID = "0000ffe9-0000-1000-8000-00805f9b34fb"

/** CAN ids of the messages this library sends or decodes. */
object InmotionLegacyIds {
    /** Fast info: realtime telemetry request, answered with an extended message. */
    const val FAST_INFO: Int = 0x0F550113

    /** Slow info: identity and settings request, answered with an extended message. */
    const val SLOW_INFO: Int = 0x0F550114

    /** Session PIN. The wheel answers with a short message of the same id. */
    const val PIN_CODE: Int = 0x0F550307

    /** Alert pushed by the wheel on its own. */
    const val ALERT: Int = 0x0F780101

    /**
     * Sent by a V8S on its own right after connecting, several times until the PIN arrives
     * (real capture). Its meaning is not established and nothing here answers it.
     */
    const val WHEEL_HELLO: Int = 0x0F060101

    /**
     * Every id a legacy wheel is known to put on the wire: the read replies above plus the
     * acknowledgements of the setting commands other apps send. Used only to recognise frames.
     */
    private val knownIds: Set<Int> = setOf(
        FAST_INFO,
        SLOW_INFO,
        PIN_CODE,
        ALERT,
        WHEEL_HELLO,
        0x0F550115, // ride mode settings acknowledgement
        0x0F550116, // remote control acknowledgement
        0x0F550119, // calibration acknowledgement
        0x0F55010D, // headlight acknowledgement
        0x0F55012E, // handle button acknowledgement
        0x0F55060A, // speaker volume acknowledgement
        0x0F550609, // play sound acknowledgement
    )

    fun isKnown(id: Int): Boolean = id in knownIds
}

/**
 * One unescaped ezcan message.
 *
 * [extra] is non-null exactly when [length] is 0xFE, and then its size equals the u32 LE in
 * `data[0..3]`. Short messages carry their payload in [data] alone.
 */
class InmotionLegacyMessage(
    val id: Int,
    val data: ByteArray,
    val length: Int,
    val channel: Int,
    val format: Int,
    val type: Int,
    val extra: ByteArray?,
) {
    init {
        require(data.size == 8) { "ezcan data is always 8 bytes" }
        require(length in 0..0xFF && channel in 0..0xFF && format in 0..0xFF && type in 0..0xFF) {
            "ezcan header fields are single bytes"
        }
        if (extra != null) {
            require(length == InmotionLegacyFraming.EXTENDED_LENGTH) { "extra requires len 0xFE" }
            require(data.ezU32Le(0) == extra.size.toLong()) { "data[0..3] must hold the extra length" }
        } else {
            require(length != InmotionLegacyFraming.EXTENDED_LENGTH) { "len 0xFE requires extra" }
        }
    }

    val isExtended: Boolean
        get() = extra != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InmotionLegacyMessage) return false
        return id == other.id &&
            data.contentEquals(other.data) &&
            length == other.length &&
            channel == other.channel &&
            format == other.format &&
            type == other.type &&
            extraEquals(extra, other.extra)
    }

    private fun extraEquals(left: ByteArray?, right: ByteArray?): Boolean =
        if (left == null || right == null) left == null && right == null else left.contentEquals(right)

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + data.contentHashCode()
        result = 31 * result + length
        result = 31 * result + channel
        result = 31 * result + format
        result = 31 * result + type
        result = 31 * result + (extra?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "InmotionLegacyMessage(id=0x${id.toUInt().toString(16)}, len=$length, ch=$channel, " +
            "format=$format, type=$type, extra=${extra?.size ?: "none"})"
}

/** Encoder and single-frame decoder for the ezcan wire format. */
object InmotionLegacyFraming {
    const val HEADER_BYTE: Int = 0xAA
    const val TAIL_BYTE: Int = 0x55
    const val ESCAPE_BYTE: Int = 0xA5
    const val EXTENDED_LENGTH: Int = 0xFE

    /**
     * Upper bound on an accepted extra payload. The largest a wheel is seen to send is 149
     * bytes; the bound only keeps a corrupted length field from growing a buffer forever.
     */
    const val MAX_EXTRA_LENGTH: Int = 1024

    /** Size of id + data + len + channel + format + type. */
    const val HEADER_FIELDS_SIZE: Int = 16

    /** Wire bytes of [message], escaping everything between header and tail, check included. */
    fun encode(message: InmotionLegacyMessage): ByteArray {
        val body = ArrayList<Int>(HEADER_FIELDS_SIZE + (message.extra?.size ?: 0) + 1)
        body += message.id and 0xFF
        body += (message.id ushr 8) and 0xFF
        body += (message.id ushr 16) and 0xFF
        body += (message.id ushr 24) and 0xFF
        message.data.forEach { body += it.toInt() and 0xFF }
        body += message.length
        body += message.channel
        body += message.format
        body += message.type
        message.extra?.forEach { body += it.toInt() and 0xFF }
        body += body.sum() and 0xFF

        val out = ArrayList<Byte>(body.size * 2 + 4)
        out += HEADER_BYTE.toByte()
        out += HEADER_BYTE.toByte()
        for (value in body) {
            if (value == HEADER_BYTE || value == TAIL_BYTE || value == ESCAPE_BYTE) {
                out += ESCAPE_BYTE.toByte()
            }
            out += value.toByte()
        }
        out += TAIL_BYTE.toByte()
        out += TAIL_BYTE.toByte()
        return out.toByteArray()
    }

    /**
     * Decodes exactly one complete wire frame, `AA AA ... 55 55`. Returns null on a wrong
     * header or tail, an unescaped marker inside the frame, a bad checksum, or a length field
     * that disagrees with the bytes present.
     */
    fun decodeFrame(frame: ByteArray): InmotionLegacyMessage? {
        if (frame.size < 4) return null
        if (frame.ezU8(0) != HEADER_BYTE || frame.ezU8(1) != HEADER_BYTE) return null
        if (frame.ezU8(frame.size - 1) != TAIL_BYTE || frame.ezU8(frame.size - 2) != TAIL_BYTE) return null
        val body = ArrayList<Byte>(frame.size)
        var index = 2
        val end = frame.size - 2
        while (index < end) {
            val value = frame.ezU8(index)
            when (value) {
                ESCAPE_BYTE -> {
                    if (index + 1 >= end) return null
                    body += frame[index + 1]
                    index += 2
                }
                HEADER_BYTE, TAIL_BYTE -> return null
                else -> {
                    body += frame[index]
                    index += 1
                }
            }
        }
        return parseUnescapedBody(body.toByteArray())
    }

    /**
     * Parses the unescaped bytes from id through check. Strict where the reference decoder is
     * lenient: a short message must not carry trailing bytes, and an extended one must carry
     * exactly the length its data field announces.
     */
    fun parseUnescapedBody(body: ByteArray): InmotionLegacyMessage? {
        if (body.size < HEADER_FIELDS_SIZE + 1) return null
        var sum = 0
        for (index in 0 until body.size - 1) sum += body.ezU8(index)
        if ((sum and 0xFF) != body.ezU8(body.size - 1)) return null

        val id = body.ezU32Le(0).toInt()
        val data = body.copyOfRange(4, 12)
        val length = body.ezU8(12)
        val extraSize = body.size - HEADER_FIELDS_SIZE - 1
        val extra = if (length == EXTENDED_LENGTH) {
            if (extraSize > MAX_EXTRA_LENGTH || data.ezU32Le(0) != extraSize.toLong()) return null
            body.copyOfRange(HEADER_FIELDS_SIZE, HEADER_FIELDS_SIZE + extraSize)
        } else {
            if (extraSize != 0) return null
            null
        }
        return InmotionLegacyMessage(
            id = id,
            data = data,
            length = length,
            channel = body.ezU8(13),
            format = body.ezU8(14),
            type = body.ezU8(15),
            extra = extra,
        )
    }
}

/**
 * Streaming reassembler for notify chunks. Chunks may split a frame anywhere, including between
 * an escape byte and the byte it escapes, and between the two tail bytes.
 *
 * It works on escaping rather than on the length field: inside a frame every 0xAA, 0x55 and
 * 0xA5 is escaped, so the first unescaped `55 55` is the tail, and an unescaped 0xAA can only be
 * the start of the next frame. Garbage before a header is skipped, and a frame that fails its
 * checksum or length check is dropped without losing the frame after it.
 */
class InmotionLegacyReassembler {
    private var body = ByteArray(64)
    private var bodySize = 0
    private var collecting = false
    private var previousWasHeaderByte = false
    private var escapePending = false
    private var tailPending = false

    fun reset() {
        bodySize = 0
        collecting = false
        previousWasHeaderByte = false
        escapePending = false
        tailPending = false
    }

    /** Feeds one notify value and returns every message it completed, in arrival order. */
    fun push(chunk: ByteArray): List<InmotionLegacyMessage> {
        val messages = ArrayList<InmotionLegacyMessage>(1)
        for (byte in chunk) {
            val value = byte.toInt() and 0xFF
            if (!collecting) {
                seekHeader(value)
                continue
            }
            if (tailPending) {
                if (value == InmotionLegacyFraming.TAIL_BYTE) {
                    InmotionLegacyFraming.parseUnescapedBody(body.copyOf(bodySize))?.let(messages::add)
                    abandonFrame()
                } else {
                    abandonFrame()
                    seekHeader(value)
                }
                continue
            }
            if (escapePending) {
                escapePending = false
                append(value)
                continue
            }
            when (value) {
                InmotionLegacyFraming.ESCAPE_BYTE -> escapePending = true
                InmotionLegacyFraming.TAIL_BYTE -> tailPending = true
                InmotionLegacyFraming.HEADER_BYTE -> {
                    // A third AA right after the header is still the header; later it can only
                    // be the next frame starting, so the current one is incomplete.
                    if (bodySize > 0) {
                        abandonFrame()
                        previousWasHeaderByte = true
                    }
                }
                else -> append(value)
            }
        }
        return messages
    }

    private fun seekHeader(value: Int) {
        if (value == InmotionLegacyFraming.HEADER_BYTE && previousWasHeaderByte) {
            collecting = true
            previousWasHeaderByte = false
            bodySize = 0
        } else {
            previousWasHeaderByte = value == InmotionLegacyFraming.HEADER_BYTE
        }
    }

    private fun append(value: Int) {
        if (bodySize >= MAX_BODY_SIZE) {
            abandonFrame()
            return
        }
        if (bodySize == body.size) body = body.copyOf(body.size * 2)
        body[bodySize++] = value.toByte()
    }

    private fun abandonFrame() {
        bodySize = 0
        collecting = false
        previousWasHeaderByte = false
        escapePending = false
        tailPending = false
    }

    private companion object {
        const val MAX_BODY_SIZE =
            InmotionLegacyFraming.HEADER_FIELDS_SIZE + InmotionLegacyFraming.MAX_EXTRA_LENGTH + 1
    }
}

/**
 * The requests a read-only client sends. None of them changes anything stored on the wheel.
 *
 * All are short messages on channel 5, format 0, len 8, matching the reference client.
 */
object InmotionLegacyRequests {
    const val DEFAULT_PIN: String = "000000"
    const val PIN_LENGTH: Int = 6

    private val ALL_FF = ByteArray(8) { 0xFF.toByte() }

    /** Fast info request, type 0. Wire: `AA AA 13 01 A5 55 0F FF×8 08 05 00 00 7D 55 55`. */
    fun fastInfo(): ByteArray = InmotionLegacyFraming.encode(shortMessage(InmotionLegacyIds.FAST_INFO, ALL_FF, type = 0))

    /**
     * Slow info request. Type 1 ("remote frame") as the reference client sends to every model;
     * the official app sends type 0 to V8 and later, and whether the wheel cares is unknown.
     */
    fun slowInfo(): ByteArray = InmotionLegacyFraming.encode(shortMessage(InmotionLegacyIds.SLOW_INFO, ALL_FF, type = 1))

    /** PIN message with the factory default PIN "000000". */
    fun pin(): ByteArray = pin(DEFAULT_PIN)

    /**
     * PIN message: six ASCII digits followed by two zero bytes.
     *
     * This is not a write in the sense of `ExperimentalWriteApi`: it unlocks the telemetry
     * session the way a login does and stores nothing on the wheel. Changing the PIN is a
     * different message that this library does not build. A wrong PIN is not reported by the
     * wheel in any known way; it presumably just withholds telemetry.
     *
     * @throws IllegalArgumentException when [pin] is not accepted by [normalizePin].
     */
    @Throws(IllegalArgumentException::class)
    fun pin(pin: String): ByteArray {
        val normalized = requireNotNull(normalizePin(pin)) { "PIN must be up to 6 ASCII digits" }
        val data = ByteArray(8)
        normalized.forEachIndexed { index, char -> data[index] = char.code.toByte() }
        return InmotionLegacyFraming.encode(shortMessage(InmotionLegacyIds.PIN_CODE, data, type = 0))
    }

    /**
     * Left-pads [pin] with '0' to six characters, as the reference client does, so "" becomes
     * "000000". Returns null for anything but ASCII digits or for more than six of them.
     */
    fun normalizePin(pin: String): String? {
        if (pin.length > PIN_LENGTH) return null
        if (pin.any { it !in '0'..'9' }) return null
        return pin.padStart(PIN_LENGTH, '0')
    }

    private fun shortMessage(id: Int, data: ByteArray, type: Int) = InmotionLegacyMessage(
        id = id,
        data = data.copyOf(),
        length = 8,
        channel = 5,
        format = 0,
        type = type,
        extra = null,
    )
}

/** Recognising a legacy InMotion wheel from what it sends or advertises. */
object InmotionLegacyDetection {
    /**
     * True when [value], one raw notify, starts an ezcan frame of a known id.
     *
     * A notify is usually 20 bytes, shorter than any complete frame, so this checks what the
     * first chunk does carry: `AA AA` followed by a whole 16-byte header that unescapes cleanly,
     * a known 32-bit CAN id, and consistent length, format and type bytes. A value that holds a
     * complete frame additionally has to pass the checksum. KingSong (`AA 55`), Begode
     * (`55 AA`) and the newer InMotion protocol (`AA AA` with no CAN id) do not match.
     */
    fun matchesRawNotify(value: ByteArray): Boolean {
        if (value.size < 2 || value.ezU8(0) != InmotionLegacyFraming.HEADER_BYTE ||
            value.ezU8(1) != InmotionLegacyFraming.HEADER_BYTE
        ) return false
        if (containsValidFrame(value)) return true
        val header = unescapeHeader(value) ?: return false
        val id = header.ezU32Le(0).toInt()
        if (!InmotionLegacyIds.isKnown(id)) return false
        val length = header.ezU8(12)
        val format = header.ezU8(14)
        val type = header.ezU8(15)
        if (format > 1 || type > 1 || header.ezU8(13) > 0x0F) return false
        // The frame's tail is already in this value, yet no valid frame came out of it.
        if (hasUnescapedTail(value)) return false
        return if (length == InmotionLegacyFraming.EXTENDED_LENGTH) {
            header.ezU32Le(4) <= InmotionLegacyFraming.MAX_EXTRA_LENGTH.toLong()
        } else {
            length <= 8
        }
    }

    /**
     * True when [value] starts with `AA AA` and contains at least one complete frame with a
     * valid checksum and a known id.
     */
    fun containsValidFrame(value: ByteArray): Boolean {
        if (value.size < 2 || value.ezU8(0) != InmotionLegacyFraming.HEADER_BYTE ||
            value.ezU8(1) != InmotionLegacyFraming.HEADER_BYTE
        ) return false
        return InmotionLegacyReassembler().push(value).any { InmotionLegacyIds.isKnown(it.id) }
    }

    /**
     * Model guess from the advertised name, using only the lower-case prefixes the official
     * app matches: `v5-`, `v8-`, `v8f-`, `v8s-`, `v10-`, `v10f-`. The V5 prefix covers V5, V5+,
     * V5F and V5D; slow info tells them apart. Anything else returns null.
     */
    fun guessModelFromName(name: String?): InmotionLegacyModel? {
        val lower = name?.trim()?.lowercase() ?: return null
        return when {
            lower.startsWith("v5-") -> InmotionLegacyModel.V5
            lower.startsWith("v8-") -> InmotionLegacyModel.V8
            lower.startsWith("v8f-") -> InmotionLegacyModel.V8F
            lower.startsWith("v8s-") -> InmotionLegacyModel.V8S
            lower.startsWith("v10-") -> InmotionLegacyModel.V10
            lower.startsWith("v10f-") -> InmotionLegacyModel.V10F
            else -> null
        }
    }

    /** The first 16 unescaped bytes after the header, or null if the value runs out first. */
    private fun unescapeHeader(value: ByteArray): ByteArray? {
        val header = ByteArray(InmotionLegacyFraming.HEADER_FIELDS_SIZE)
        var count = 0
        var index = 2
        while (count < header.size) {
            if (index >= value.size) return null
            when (value.ezU8(index)) {
                InmotionLegacyFraming.ESCAPE_BYTE -> {
                    if (index + 1 >= value.size) return null
                    header[count++] = value[index + 1]
                    index += 2
                }
                InmotionLegacyFraming.HEADER_BYTE, InmotionLegacyFraming.TAIL_BYTE -> return null
                else -> {
                    header[count++] = value[index]
                    index += 1
                }
            }
        }
        return header
    }

    /** True when an unescaped `55 55` follows the header, i.e. a frame ends inside [value]. */
    private fun hasUnescapedTail(value: ByteArray): Boolean {
        var index = 2
        var previousWasTail = false
        while (index < value.size) {
            val byte = value.ezU8(index)
            if (byte == InmotionLegacyFraming.ESCAPE_BYTE) {
                previousWasTail = false
                index += 2
                continue
            }
            val isTail = byte == InmotionLegacyFraming.TAIL_BYTE
            if (isTail && previousWasTail) return true
            previousWasTail = isTail
            index += 1
        }
        return false
    }
}

internal fun ByteArray.ezU8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.ezU32Le(index: Int): Long =
    (ezU8(index).toLong()) or
        (ezU8(index + 1).toLong() shl 8) or
        (ezU8(index + 2).toLong() shl 16) or
        (ezU8(index + 3).toLong() shl 24)
