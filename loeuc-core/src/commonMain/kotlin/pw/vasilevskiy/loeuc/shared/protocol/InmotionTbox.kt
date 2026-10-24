package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/**
 * The InMotion telematics box (TBox): the module that carries the built-in eSIM behind
 * "Ride Connect". It answers the same many-to-many `0x16` envelope the direct-BMS reads
 * use, addressed to its own device id rather than a battery's.
 *
 * The one read modelled here is `getTBoxEsimList` (command `0x10`), the request the
 * official app uses to list the eSIM(s) and, with them, the EID a carrier needs to
 * activate the SIM. The command byte carries no `0x80`, so nothing about the device
 * changes - this is a read.
 *
 * The response layout is not yet proven against a real capture, so [esimResponse] is
 * deliberately lenient and never decodes fields it cannot stand behind: it returns the
 * raw payload plus best-effort text/EID candidates, and a diagnostics screen shows those
 * so the actual bytes can be read off a screenshot.
 */
object InmotionTbox {
    /** The TBox device address on the many-to-many bus, as it goes on the wire. */
    const val ADDRESS = 0x2F

    /** `getTBoxEsimList` in the app's Lorin command table. */
    const val CMD_ESIM_LIST = 0x10

    /**
     * The three TBox status reads (no `0x80`, so reads): realtime info (signal/connected),
     * network config (SIM IMSI/ICCID text) and fixed info (model). Answered by the TBox
     * (source 0x2F), captured the same way as the eSIM list. The payload width of these three
     * is inferred, not proven against a capture, so callers show the raw response.
     */
    fun statusReadCommands(): List<ByteArray> = listOf(
        statusRead(CMD_REALTIME_INFO),
        statusRead(CMD_NETWORK_CONFIG),
        statusRead(CMD_FIXED_INFO),
    )

    private const val CMD_REALTIME_INFO = 0x01
    private const val CMD_NETWORK_CONFIG = 0x02
    private const val CMD_FIXED_INFO = 0x04

    /** `AA AA 16 03 01 2F <cmd> <xor>` - host source 0x01 to the TBox, no payload. */
    private fun statusRead(cmd: Int): ByteArray =
        buildInmotionV2Message(
            flag = FLAG_MANY_TO_MANY_TBOX,
            command = 0x01,
            data = byteArrayOf(ADDRESS.toByte(), cmd.toByte()),
        )

    /**
     * `AA AA 16 03 02 2F 10 <xor>` - the same `0x16` envelope [InmotionDirectBms.readCommand]
     * builds, with the host source `0x02`, the TBox target and the eSIM-list command in the
     * command slot.
     */
    fun esimListCommand(): ByteArray =
        buildInmotionV2Message(
            flag = FLAG_MANY_TO_MANY_TBOX,
            command = HOST_SOURCE_TBOX,
            data = byteArrayOf(ADDRESS.toByte(), CMD_ESIM_LIST.toByte()),
        )

    /**
     * The `openDownloadMode` sequence the official app runs before it can read the eUICC: it
     * opens the modem's APDU channel so `AT+CSIM` gets answered. Three Lorin frames addressed
     * to the TBox turn the channel on, then `ATE0` (echo off) is sent five times. Each must be
     * answered before the next; send them in order.
     *
     * These change the module's state (they enable a mode), so they carry [ExperimentalWriteApi]
     * like every other builder that writes to a device. The matching [closeDownloadModeCommands]
     * must be sent afterwards to restore it.
     */
    @ExperimentalWriteApi
    fun openDownloadModeCommands(): List<ByteArray> = listOf(
        bytes(0xAA, 0xAA, 0x16, 0x06, 0x01, 0x2F, 0x52, 0x01, 0x00, 0x01, 0x6C),
        bytes(0xAA, 0xAA, 0x16, 0x05, 0x01, 0x21, 0x60, 0x4A, 0x01, 0x18),
        bytes(0xAA, 0xAA, 0x16, 0x04, 0x01, 0x23, 0x50, 0x01, 0x61),
        ECHO_OFF, ECHO_OFF, ECHO_OFF, ECHO_OFF, ECHO_OFF,
    )

    /**
     * The `closeDownloadMode` sequence: the same three sub-commands as [openDownloadModeCommands]
     * with the mode byte cleared, in reverse order, restoring the module. Send it once the EID
     * read has finished, whether it succeeded or timed out.
     */
    @ExperimentalWriteApi
    fun closeDownloadModeCommands(): List<ByteArray> = listOf(
        bytes(0xAA, 0xAA, 0x16, 0x04, 0x01, 0x23, 0x50, 0x00, 0x60),
        bytes(0xAA, 0xAA, 0x16, 0x05, 0x01, 0x21, 0x60, 0x4A, 0x00, 0x19),
        bytes(0xAA, 0xAA, 0x16, 0x06, 0x01, 0x2F, 0x52, 0x01, 0x00, 0x00, 0x6D),
    )

    /**
     * Reboot the TBox ("重启云盒"): `AA AA 16 06 01 2F 4F 01 00 01 71`. The activation flow fires
     * this after `/v2/imei/activate` so the modem re-reads the freshly provisioned eSIM and
     * re-attaches to the network - it is the step that brings 4G up. Reboots the module, so it
     * carries [ExperimentalWriteApi].
     */
    @ExperimentalWriteApi
    fun rebootCommand(): ByteArray = bytes(0xAA, 0xAA, 0x16, 0x06, 0x01, 0x2F, 0x4F, 0x01, 0x00, 0x01, 0x71)

    private val ECHO_OFF: ByteArray get() = "ATE0\r\n".encodeToByteArray()

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    /**
     * The TBox IMEI from a getTBoxFixedInfo (cmd 0x04) answer, or null when [frame] is not that
     * answer or is too short. The IMEI is an ASCII, null-padded field at offset 14 of the
     * response payload (the bytes after src/target/cmd), per `TBoxInfoModelResponse.fromBytes`.
     */
    fun imeiFromFixedInfo(frame: ByteArray): String? {
        if (frame.size < 8) return null
        if (frame.u8t(2) != FLAG_MANY_TO_MANY_TBOX) return null
        if (frame.u8t(4) != ADDRESS) return null
        if (frame.u8t(6) and 0x7F != CMD_FIXED_INFO) return null
        val payloadStart = 7
        val imeiStart = payloadStart + 14
        val imeiEnd = minOf(payloadStart + 60, frame.size - 1)
        if (imeiEnd <= imeiStart) return null
        val text = buildString {
            for (i in imeiStart until imeiEnd) {
                val c = frame.u8t(i)
                if (c == 0) break
                if (c in 0x20..0x7E) append(c.toChar())
            }
        }.trim()
        return text.takeIf { it.length >= 8 && it.all { ch -> ch.isDigit() } }
    }

    /**
     * The TBox `user_name` and `pwd` ("UserInfo") from a getTBoxNetworkConfig (cmd 0x02) answer,
     * or null when [frame] is not that answer. Two ASCII, null-padded fields in the response
     * payload at offsets 4 and 37 (per `TBoxNetworkConfigResponse.fromBytes`, which caps them at
     * 74 and 140). These feed the AES `data` of /v2/imei/check - they are the TBox's own
     * credentials, read from the wheel, NOT the app account login.
     */
    fun userInfoFromNetworkConfig(frame: ByteArray): Pair<String, String>? {
        if (frame.size < 8) return null
        if (frame.u8t(2) != FLAG_MANY_TO_MANY_TBOX) return null
        if (frame.u8t(4) != ADDRESS) return null
        if (frame.u8t(6) and 0x7F != CMD_NETWORK_CONFIG) return null
        val payloadStart = 7
        val userName = frame.asciiField(payloadStart + 4, payloadStart + 74)
        val pwd = frame.asciiField(payloadStart + 37, payloadStart + 140)
        if (userName.isEmpty() || pwd.isEmpty()) return null
        return userName to pwd
    }

    /**
     * A NUL-padded ASCII field: stop at the first NUL and keep only printable ASCII. The `user_name`
     * and `pwd` windows the TBox reports overlap (4:74 and 37:140), so decoding the whole window and
     * only stripping NUL would glue the pwd onto the user_name and drag in a stray high byte the
     * field carries after the value; the /v2/imei/check backend rejects that as "invalid params"
     * (error 30001). Stopping at the first NUL and dropping non-printables yields the clean credential
     * the server expects (a still-unbound imei then comes back as its own "Invalid IMEI", not a
     * malformed-request error).
     */
    private fun ByteArray.asciiField(start: Int, end: Int): String = buildString {
        val limit = minOf(end, this@asciiField.size)
        var i = start
        while (i < limit) {
            val c = this@asciiField[i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) append(c.toChar())
            i++
        }
    }.trim()

    /**
     * True when a frame looks like an answer coming back from the TBox: either a
     * many-to-many frame whose source is [ADDRESS], or an ordinary frame echoing the
     * eSIM-list command with the read bit set. Kept wide on purpose - an unknown response
     * shape must still reach the screen rather than be silently dropped.
     */
    fun isEsimResponseFrame(frame: ByteArray): Boolean {
        if (frame.size < 6) return false
        val flag = frame.u8t(2)
        if (flag == FLAG_MANY_TO_MANY_TBOX && frame.u8t(4) == ADDRESS) return true
        return frame.u8t(4) and 0x7F == CMD_ESIM_LIST
    }

    /**
     * The raw payload of a TBox answer plus any printable strings and EID candidate found
     * in it, or null when the frame is not a TBox answer. No field is decoded by offset -
     * the caller shows [InmotionTboxResponse.rawFrame] and the candidates verbatim.
     */
    fun esimResponse(frame: ByteArray): InmotionTboxResponse? {
        if (!isEsimResponseFrame(frame)) return null
        if (frame.size < 6) return null
        val declaredLen = frame.u8t(3)
        // Payload begins after header(2) + flag + len + the source/target/command triple of
        // the many-to-many envelope; for a plain frame it begins after the single command
        // byte. Clamp to the checksum either way so a short or malformed frame cannot throw.
        val payloadStart = if (frame.u8t(2) == FLAG_MANY_TO_MANY_TBOX) 7 else 5
        val payloadEnd = minOf(4 + declaredLen, frame.size - 1)
        val payload = if (payloadEnd > payloadStart) {
            frame.copyOfRange(payloadStart, payloadEnd)
        } else {
            ByteArray(0)
        }
        val strings = payload.printableRuns(minLength = 4)
        return InmotionTboxResponse(
            rawFrame = frame.copyOf(),
            payload = payload,
            printableStrings = strings,
            eidCandidate = strings.firstNotNullOfOrNull { it.eidCandidate() }
                ?: payload.hexDigits().eidCandidate(),
        )
    }

    /**
     * A 32-digit run inside a longer string, the shape of a GSMA EID (and the leading 32
     * digits of the way it is usually printed). Null when nothing that long is present.
     */
    private fun String.eidCandidate(): String? {
        val digits = filter { it.isDigit() }
        val match = Regex("\\d{32}").find(digits) ?: return null
        return match.value
    }

    private fun ByteArray.hexDigits(): String =
        joinToString("") { byte ->
            val v = byte.toInt() and 0xFF
            v.toString(16).padStart(2, '0')
        }

    private fun ByteArray.printableRuns(minLength: Int): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        for (byte in this) {
            val c = byte.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                if (current.length >= minLength) runs.add(current.toString())
                current.clear()
            }
        }
        if (current.length >= minLength) runs.add(current.toString())
        return runs
    }

    private fun ByteArray.u8t(offset: Int): Int = this[offset].toInt() and 0xFF
}

/** The host source `0x02` the app hardcodes for itself on the many-to-many bus. */
private const val HOST_SOURCE_TBOX = 0x02

private const val FLAG_MANY_TO_MANY_TBOX = 0x16

/**
 * One answer from the TBox. [rawFrame] and [payload] are always populated; the rest are
 * best-effort hints extracted without committing to a field layout.
 */
data class InmotionTboxResponse(
    val rawFrame: ByteArray,
    val payload: ByteArray,
    val printableStrings: List<String>,
    val eidCandidate: String?,
) {
    override fun equals(other: Any?): Boolean {
        return other is InmotionTboxResponse &&
            rawFrame.contentEquals(other.rawFrame) &&
            payload.contentEquals(other.payload) &&
            printableStrings == other.printableStrings &&
            eidCandidate == other.eidCandidate
    }

    override fun hashCode(): Int {
        var result = rawFrame.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + printableStrings.hashCode()
        result = 31 * result + (eidCandidate?.hashCode() ?: 0)
        return result
    }
}
