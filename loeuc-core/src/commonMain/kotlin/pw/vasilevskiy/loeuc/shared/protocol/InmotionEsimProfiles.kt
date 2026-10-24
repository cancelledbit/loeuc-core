package pw.vasilevskiy.loeuc.shared.protocol

/**
 * One eUICC profile as reported by ES10c GetProfilesInfo (`BF2D`). Reproduced clean-room from the
 * Redtea Mobile `liblpa.so` the official InMotion app ships (it builds the APDUs in a native SDK,
 * not in Dart); see docs/inmotion-tbox-ride-connect-notes.md. Reading this device-side answers
 * "did the business profile actually arrive and get enabled" without the backend.
 */
data class InmotionEsimProfile(
    /** ICCID, 19-20 digits: BCD nibble-swapped, trailing `F` padding removed. */
    val iccid: String,
    /** profileState (tag `9F70`): 0 = disabled, 1 = enabled, -1 when the tag was absent. */
    val state: Int,
    /** profileClass (tag `95`): 0 = test, 1 = provisioning (bootstrap/seed), 2 = operational, -1 absent. */
    val profileClass: Int,
) {
    /** The eUICC currently has this profile enabled. */
    val enabled: Boolean get() = state == 1

    /** The operational "business" profile a seller activates - its ICCID starts `8944`. */
    val isBusiness: Boolean get() = iccid.startsWith("8944")

    /** The provisioning "bootstrap/seed" profile the wheel ships with - ICCID starts `8900`. */
    val isBootstrap: Boolean get() = iccid.startsWith("8900")
}

/**
 * Builds the "list all profiles" request and parses its response. The request is sent as an
 * `AT+CSIM` STORE DATA on the same ISD-R logical channel [InmotionEidReader] opens for GetEID.
 */
object InmotionEsimProfiles {

    /**
     * ES10c GetProfilesInfo "list all" as a STORE DATA APDU: `81 E2 91 00 03 BF2D00`
     * (CLA `0x80|channel` with channel 1, INS `E2`, P1 `0x91` = single/last block, Lc `03` for the
     * three payload bytes). `BF2D00` is the empty `ProfileInfoListRequest`, i.e. every profile with
     * the default field set. Fits in one 50-byte STORE DATA block, so no block chaining is needed on
     * the way out; the *response* can still span several `61 XX` GET RESPONSE reads, which the caller
     * stitches together before calling [parse].
     */
    const val GET_ALL_APDU_HEX: String = "81E2910003BF2D00"

    /**
     * Parse a GetProfilesInfo response: `BF2D` -> `A0` -> one `E3` per profile, each carrying ICCID
     * (`5A`, BCD nibble-swapped), profileState (`9F70`) and profileClass (`95`). [responseHex] may
     * be the raw `+CSIM` value with a trailing `9000`; anything before the `BF2D` tag is skipped.
     * Returns an empty list when no `BF2D`/`A0` structure is present.
     */
    fun parse(responseHex: String): List<InmotionEsimProfile> {
        val bytes = hexToBytes(responseHex)
        // The response data is the BF2D TLV, but a +CSIM echo may prepend a header, so locate the
        // tag by its raw bytes rather than walking TLVs from offset 0 (a header can look like a TLV
        // and swallow the real tag).
        val bf2dAt = indexOfTag(bytes, 0xBF, 0x2D)
        if (bf2dAt < 0) return emptyList()
        val bf2d = readTlv(bytes, bf2dAt) ?: return emptyList()
        val a0 = findTag(bytes, bf2d.valueStart, bf2d.end, 0xA0) ?: return emptyList()
        val profiles = mutableListOf<InmotionEsimProfile>()
        var offset = a0.valueStart
        while (offset < a0.end) {
            val entry = readTlv(bytes, offset) ?: break
            if (entry.tag == 0xE3) {
                val iccid = findTag(bytes, entry.valueStart, entry.end, 0x5A)
                    ?.let { iccidFromBcd(bytes, it.valueStart, it.valueLen) }
                    .orEmpty()
                if (iccid.isNotEmpty()) {
                    profiles.add(
                        InmotionEsimProfile(
                            iccid = iccid,
                            state = findTag(bytes, entry.valueStart, entry.end, 0x9F70)
                                ?.let { integerOf(bytes, it) } ?: -1,
                            profileClass = findTag(bytes, entry.valueStart, entry.end, 0x95)
                                ?.let { integerOf(bytes, it) } ?: -1,
                        ),
                    )
                }
            }
            offset = entry.end
        }
        return profiles
    }

    /** One decoded BER-TLV: its [tag], where its value starts, its value length and the next TLV. */
    private class Tlv(val tag: Int, val valueStart: Int, val valueLen: Int) {
        val end: Int get() = valueStart + valueLen
    }

    /** Decode the BER-TLV at [offset], or null when it runs past the buffer. */
    private fun readTlv(bytes: ByteArray, offset: Int): Tlv? {
        if (offset >= bytes.size) return null
        var i = offset
        var tag = bytes[i].toInt() and 0xFF
        i++
        if (tag and 0x1F == 0x1F) {
            while (true) {
                if (i >= bytes.size) return null
                val next = bytes[i].toInt() and 0xFF
                tag = (tag shl 8) or next
                i++
                if (next and 0x80 == 0) break
            }
        }
        if (i >= bytes.size) return null
        var length = bytes[i].toInt() and 0xFF
        i++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            if (count == 0 || count > 3 || i + count > bytes.size) return null
            length = 0
            repeat(count) {
                length = (length shl 8) or (bytes[i].toInt() and 0xFF)
                i++
            }
        }
        if (i + length > bytes.size) return null
        return Tlv(tag, i, length)
    }

    /** The offset of the first `[hi, lo]` two-byte tag pattern, or -1. */
    private fun indexOfTag(bytes: ByteArray, hi: Int, lo: Int): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i].toInt() and 0xFF == hi && bytes[i + 1].toInt() and 0xFF == lo) return i
        }
        return -1
    }

    /** The first TLV with [tag] in `[start, limit)`, or null. */
    private fun findTag(bytes: ByteArray, start: Int, limit: Int, tag: Int): Tlv? {
        var offset = start
        while (offset < limit) {
            val tlv = readTlv(bytes, offset) ?: return null
            if (tlv.tag == tag) return tlv
            offset = tlv.end
        }
        return null
    }

    /** A big-endian integer from a short value field (profileState/profileClass are one byte). */
    private fun integerOf(bytes: ByteArray, tlv: Tlv): Int {
        var value = 0
        for (i in tlv.valueStart until minOf(tlv.end, bytes.size)) {
            value = (value shl 8) or (bytes[i].toInt() and 0xFF)
        }
        return value
    }

    /** ICCID from its BCD field: swap each byte's nibbles, then drop the trailing `F` padding. */
    private fun iccidFromBcd(bytes: ByteArray, start: Int, length: Int): String {
        val digits = StringBuilder()
        for (i in start until minOf(start + length, bytes.size)) {
            val value = bytes[i].toInt() and 0xFF
            digits.append(HEX[value and 0x0F])
            digits.append(HEX[value ushr 4])
        }
        return digits.toString().trimEnd('f', 'F').uppercase()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val size = clean.length / 2
        return ByteArray(size) { index ->
            ((clean[index * 2].digitToInt(16) shl 4) or clean[index * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
