package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/**
 * Reads the eUICC EID from an InMotion TBox, reproducing the official app's `getEid` flow
 * step by step and, like it, waiting for each step to be answered before sending the next.
 *
 * The flow has two kinds of step:
 *  - Wake (`openDownloadMode`): three Lorin `AA AA 16` frames to the TBox that open the
 *    modem's APDU channel, each answered by a frame coming back *from* the TBox, then `ATE0`
 *    (echo off) sent five times, each answered by `OK`. Without this the modem ignores
 *    `AT+CSIM` entirely.
 *  - CSIM: `AT+CSIM` APDUs - open a logical channel, SELECT ISD-R, ES10c GetEID, ES10c
 *    GetProfilesInfo, then close the channel - each answered by a `+CSIM: …OK`. The EID is the
 *    value of tag `5A` inside the `BF3E` response; the profiles come from the `BF2D` response
 *    (which may span 61 XX GET RESPONSE reads).
 *  - Network status: plain `AT` queries (`+CSQ`, `+CREG?`, `+CEREG?`, `+COPS?`, `+CGATT?`,
 *    `+CEER`) between the profiles and the channel close, so the transcript shows whether the
 *    modem is registered and, when it is denied, why - an enabled profile can still be refused by
 *    the carrier when the subscription behind it is not active.
 *
 * `closeDownloadMode` is left to the caller so it can always run, even if this driver times
 * out mid-flow. Nothing here is a coroutine: [start] gives the first bytes to write, and each
 * [onNotify] returns whether to keep waiting, write the next step, or finish.
 */
@OptIn(ExperimentalWriteApi::class)
class InmotionEidReader {

    private sealed interface Step
    private class LorinStep(val bytes: ByteArray) : Step {
        /**
         * The address this command is aimed at (its target byte), which is the source address
         * its answer comes back with. The three wake frames target different nodes - the TBox
         * (0x2F) and two others (0x21, 0x23) - so each must wait for its own responder, not a
         * single hard-coded address.
         */
        val answerSource: Int = if (bytes.size > 5) bytes[5].toInt() and 0xFF else InmotionTbox.ADDRESS
    }
    private class AtStep(
        val text: String,
        val parsesEid: Boolean = false,
        val parsesProfiles: Boolean = false,
        prefix: String? = null,
    ) : Step {
        /**
         * The line that marks this command's own answer, so the constant `data error` / stray
         * `OK`/`ERROR` noise before it does not complete the step. `AT+CSIM` answers with
         * `+CSIM:`; a status query answers with its own `+...:` line (passed in); a bare command
         * (`ATE0`) has no prefix and just waits for a terminator.
         */
        val responsePrefix: String? = prefix ?: if (text.contains("AT+CSIM")) "+CSIM:" else null
    }

    private val steps: List<Step> = buildList {
        InmotionTbox.openDownloadModeCommands().forEach { command ->
            if (command.isLorinFrame()) add(LorinStep(command)) else add(AtStep(command.decodeToString()))
        }
        add(AtStep("AT+CSIM=10,\"0070000001\"\r\n"))
        add(AtStep("AT+CSIM=42,\"01A4040010A0000005591010FFFFFFFF8900000100\"\r\n"))
        add(AtStep("AT+CSIM=22,\"81E2910006BF3E035C015A\"\r\n", parsesEid = true))
        // ES10c GetProfilesInfo on the same ISD-R channel: which eSIM profiles the eUICC holds and
        // which one is enabled (81 E2 91 00 03 BF2D00). Its answer may span several 61 XX GET
        // RESPONSE reads, stitched together before the profiles are parsed.
        add(AtStep("AT+CSIM=16,\"${InmotionEsimProfiles.GET_ALL_APDU_HEX}\"\r\n", parsesProfiles = true))
        // Why the modem is (or is not) on the network. A profile can be enabled yet the carrier
        // deny registration (CREG/CEREG stat 3) when the subscription behind it is not active, so
        // read the 2G/3G and LTE registration state, the operator, packet attach, signal and the
        // last EMM/session reject cause. Each waits for its own "+...:" answer line.
        add(AtStep("AT+CSQ\r\n", prefix = "+CSQ:"))
        add(AtStep("AT+CREG?\r\n", prefix = "+CREG:"))
        add(AtStep("AT+CEREG?\r\n", prefix = "+CEREG:"))
        add(AtStep("AT+COPS?\r\n", prefix = "+COPS:"))
        add(AtStep("AT+CGATT?\r\n", prefix = "+CGATT:"))
        add(AtStep("AT+CEER\r\n", prefix = "+CEER:"))
        add(AtStep("AT+CSIM=10,\"0070800100\"\r\n"))
    }

    private var index: Int = -1
    private val buffer = StringBuilder()
    private val transcript = mutableListOf<String>()
    private val rawNotifies = mutableListOf<String>()

    private val MAX_BUFFER = 4096

    var eid: String? = null
        private set

    /** The eUICC profiles from the GetProfilesInfo step: their ICCIDs, class and enabled state. */
    var profiles: List<InmotionEsimProfile> = emptyList()
        private set

    /** GetProfilesInfo response data accumulated across 61 XX GET RESPONSE reads. */
    private val profileResponseHex = StringBuilder()

    var finished: Boolean = false
        private set

    /** The bytes of the first step; write these, then feed answers to [onNotify]. */
    fun start(): ByteArray {
        index = 0
        buffer.clear()
        transcript.clear()
        rawNotifies.clear()
        eid = null
        profiles = emptyList()
        profileResponseHex.clear()
        finished = false
        return steps[0].bytes()
    }

    /**
     * Feed one BLE notification. A wake step advances when the TBox answers (a frame from
     * source `0x2F`); an AT step accumulates text until `OK`/`ERROR`. Ordinary telemetry that
     * matches neither is ignored, so it neither advances a step nor pollutes the log.
     */
    fun onNotify(bytes: ByteArray): InmotionEidAction {
        if (index < 0 || finished) return InmotionEidAction.NeedMore
        return when (val step = steps[index]) {
            is LorinStep -> onLorinNotify(bytes, step)
            is AtStep -> onAtNotify(bytes, step)
        }
    }

    /**
     * Abandon the current step when it never answered (the caller's per-step retries ran out) and
     * move on, returning the next action. This keeps one silent command - a status query the modem
     * does not support, say - from stranding the steps after it; the EID and profiles gathered so
     * far are kept.
     */
    fun skip(): InmotionEidAction {
        if (index < 0 || finished) return InmotionEidAction.NeedMore
        transcript.add("(no answer, skipped)")
        buffer.clear()
        return advance()
    }

    private fun onLorinNotify(bytes: ByteArray, step: LorinStep): InmotionEidAction {
        // Log every AA AA 16 frame during a wake step - the answer and any BMS/telemetry frame
        // in the same family - so a stuck wake is visible; advance only on the answer from the
        // node this command was aimed at.
        if (bytes.isManyToManyFrame()) logRaw(bytes.hex())
        if (!bytes.isAnswerFrom(step.answerSource)) return InmotionEidAction.NeedMore
        transcript.add("wake: ${bytes.hex()}")
        return advance()
    }

    private fun onAtNotify(bytes: ByteArray, step: AtStep): InmotionEidAction {
        if (!bytes.looksLikeModemText()) return InmotionEidAction.NeedMore
        logRaw(bytes.hex())
        buffer.append(bytes.decodeToString())
        if (buffer.length > MAX_BUFFER) buffer.deleteRange(0, buffer.length - MAX_BUFFER)
        val response = buffer.toString()
        val completeAt = step.responsePrefix?.let { response.prefixResponseEnd(it) } ?: response.terminatorEnd()
        if (completeAt < 0) return InmotionEidAction.NeedMore
        // Log a trimmed tail so the constant "data error" noise does not fill the transcript.
        val stepResponse = response.substring(0, completeAt).trim().takeLast(80)
        transcript.add(stepResponse)
        if (step.parsesEid) {
            eid = extractCsimValue(response)?.let { extractEid(it) }
        }
        if (step.parsesProfiles) {
            handleProfilesResponse(response)?.let { return it }
        }
        return advance()
    }

    /**
     * Accumulate a GetProfilesInfo response across 61 XX GET RESPONSE reads. Returns a GET RESPONSE
     * step to send (staying on this step) while more bytes are waiting, or null once the status is
     * 90 00 (or an error) - at which point [profiles] is parsed, summarised into the transcript,
     * and the driver advances to close the channel.
     */
    private fun handleProfilesResponse(response: String): InmotionEidAction? {
        val value = extractCsimValue(response) ?: return null
        if (value.length < 4) return null
        val statusWord = value.takeLast(4)
        profileResponseHex.append(value.dropLast(4))
        if (statusWord.startsWith("61")) {
            // 61 XX: XX more bytes are waiting - pull them with GET RESPONSE (CLA = bare channel 1).
            val apdu = "01C00000" + statusWord.substring(2)
            buffer.clear()
            return InmotionEidAction.Send("AT+CSIM=${apdu.length},\"$apdu\"\r\n".encodeToByteArray())
        }
        profiles = InmotionEsimProfiles.parse(profileResponseHex.toString())
        profiles.forEach { profile ->
            val kind = when {
                profile.isBusiness -> "business"
                profile.isBootstrap -> "bootstrap"
                else -> "class ${profile.profileClass}"
            }
            transcript.add("profile ${profile.iccid} $kind ${if (profile.enabled) "ENABLED" else "disabled"}")
        }
        return null
    }

    /** Index just past the terminator of a plain AT reply (`OK`/`ERROR`), or -1. */
    private fun String.terminatorEnd(): Int {
        val ok = indexOf("OK")
        val err = indexOf("ERROR")
        val at = listOf(ok, err).filter { it >= 0 }.minOrNull() ?: return -1
        return at + (if (at == ok) 2 else 5)
    }

    /** Index just past the `OK`/`ERROR` that follows a [prefix] line (e.g. `+CSIM:`), or -1. */
    private fun String.prefixResponseEnd(prefix: String): Int {
        val at = indexOf(prefix)
        if (at < 0) return -1
        val after = substring(at)
        val end = after.terminatorEnd()
        return if (end < 0) -1 else at + end
    }

    private fun advance(): InmotionEidAction {
        index += 1
        buffer.clear()
        return if (index < steps.size) {
            InmotionEidAction.Send(steps[index].bytes())
        } else {
            finished = true
            InmotionEidAction.Done(eid = eid, transcript = transcript.toList())
        }
    }

    fun transcriptSoFar(): List<String> = transcript.toList()

    fun rawNotifyLog(): List<String> = rawNotifies.toList()

    /** Append a notify's hex, collapsing a run of identical ones into one `hex ×N` line. */
    private fun logRaw(hex: String) {
        val last = rawNotifies.lastOrNull()
        if (last != null && last.substringBefore(" ×") == hex) {
            val count = last.substringAfter(" ×", "1").toIntOrNull() ?: 1
            rawNotifies[rawNotifies.lastIndex] = "$hex ×${count + 1}"
        } else if (rawNotifies.size < 64) {
            rawNotifies.add(hex)
        }
    }

    /** How many of the [stepsTotal] steps have been answered, for a progress line. */
    fun stepsCompleted(): Int = if (index < 0) 0 else index

    fun stepsTotal(): Int = steps.size

    private fun Step.bytes(): ByteArray = when (this) {
        is LorinStep -> bytes
        is AtStep -> text.encodeToByteArray()
    }

    /** The hex string between the two quotes of the `+CSIM: <len>,"<hex>"` line. */
    private fun extractCsimValue(response: String): String? {
        val csim = response.indexOf("+CSIM:")
        val from = if (csim >= 0) csim else 0
        val first = response.indexOf('"', from)
        if (first < 0) return null
        val second = response.indexOf('"', first + 1)
        if (second <= first) return null
        return response.substring(first + 1, second).trim().uppercase()
    }

    /**
     * The 32 hex characters of the EID: the value of tag `5A` (length `0x10`) inside the
     * `BF3E` GetEID response. Null when the TLV is absent or truncated.
     */
    private fun extractEid(valueHex: String): String? {
        val hex = valueHex.uppercase()
        val bf3e = hex.indexOf("BF3E")
        val from = if (bf3e >= 0) bf3e else 0
        val tag = hex.indexOf("5A10", from)
        if (tag < 0) return null
        val start = tag + 4
        if (start + 32 > hex.length) return null
        return hex.substring(start, start + 32)
    }

    /** A modem reply is printable ASCII and never a Lorin `AA AA` frame or binary telemetry. */
    private fun ByteArray.looksLikeModemText(): Boolean {
        if (isEmpty()) return false
        if (isLorinFrame()) return false
        val printable = count { byte ->
            val v = byte.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x0D || v == 0x0A
        }
        return printable * 2 >= size
    }

    private fun ByteArray.isLorinFrame(): Boolean =
        size >= 2 && this[0] == 0xAA.toByte() && this[1] == 0xAA.toByte()

    private fun ByteArray.isManyToManyFrame(): Boolean =
        size >= 5 && isLorinFrame() && (this[2].toInt() and 0xFF) == 0x16

    /** A many-to-many frame whose source is [address]: the answer to a command aimed there. */
    private fun ByteArray.isAnswerFrom(address: Int): Boolean =
        isManyToManyFrame() && (this[4].toInt() and 0xFF) == address

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** What [InmotionEidReader.onNotify] wants next. */
sealed class InmotionEidAction {
    /** The current step's response is not complete; keep feeding notifications. */
    object NeedMore : InmotionEidAction()

    /** Write these bytes (the next step) to the wheel. */
    data class Send(val bytes: ByteArray) : InmotionEidAction() {
        override fun equals(other: Any?): Boolean =
            other is Send && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** The sequence finished. [eid] is null when no EID TLV was returned. */
    data class Done(val eid: String?, val transcript: List<String>) : InmotionEidAction()
}
