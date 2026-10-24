package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InmotionEsimProfilesTest {

    @Test
    fun getAllApduIsTheEmptyBf2dStoreDataCommand() {
        // 81 E2 91 00 03 BF2D00: STORE DATA (CLA 0x80|ch1, INS E2, P1 0x91 last block), payload
        // BF2D00 = "list all profiles", exactly what liblpa's rt_lpa_get_profile_info emits.
        assertEquals("81E2910003BF2D00", InmotionEsimProfiles.GET_ALL_APDU_HEX)
    }

    @Test
    fun parsesBootstrapAndBusinessProfilesWithStateAndClass() {
        val seed = profileTlv(iccid = "89000000000000000001", state = 0, profileClass = 1)
        val business = profileTlv(iccid = "89440000000007849349", state = 1, profileClass = 2)
        val bf2d = tlv(listOf(0xBF, 0x2D), tlv(listOf(0xA0), seed + business))
        // The +CSIM value carries junk (an echoed header) before the tag and a 9000 status after.
        val responseHex = "0102" + bf2d.toHex() + "9000"

        val profiles = InmotionEsimProfiles.parse(responseHex)

        assertEquals(2, profiles.size)
        val boot = profiles[0]
        assertEquals("89000000000000000001", boot.iccid)
        assertEquals(0, boot.state)
        assertEquals(1, boot.profileClass)
        assertFalse(boot.enabled)
        assertTrue(boot.isBootstrap)
        assertFalse(boot.isBusiness)

        val biz = profiles[1]
        assertEquals("89440000000007849349", biz.iccid)
        assertEquals(1, biz.state)
        assertEquals(2, biz.profileClass)
        assertTrue(biz.enabled)
        assertTrue(biz.isBusiness)
        assertFalse(biz.isBootstrap)
    }

    @Test
    fun iccidPaddingFIsTrimmedAndNibblesAreSwapped() {
        // A 19-digit ICCID is stored as 10 BCD bytes with a trailing F nibble; it must come back
        // without the padding.
        val profile = profileTlv(iccid = "8944000000000784934", state = 1, profileClass = 2)
        val bf2d = tlv(listOf(0xBF, 0x2D), tlv(listOf(0xA0), profile))
        val profiles = InmotionEsimProfiles.parse(bf2d.toHex())
        assertEquals("8944000000000784934", profiles.single().iccid)
    }

    @Test
    fun aResponseWithoutBf2dYieldsNoProfiles() {
        assertTrue(InmotionEsimProfiles.parse("6f00a5a5a5").isEmpty())
        assertTrue(InmotionEsimProfiles.parse("").isEmpty())
    }

    /** An `E3` profile carrying ICCID (`5A`), profileState (`9F70`) and profileClass (`95`). */
    private fun profileTlv(iccid: String, state: Int, profileClass: Int): ByteArray = tlv(
        listOf(0xE3),
        tlv(listOf(0x5A), iccidToBcd(iccid)) +
            tlv(listOf(0x9F, 0x70), byteArrayOf(state.toByte())) +
            tlv(listOf(0x95), byteArrayOf(profileClass.toByte())),
    )

    /** A short-form BER-TLV: the [tag] bytes, a one-byte length, then [value]. */
    private fun tlv(tag: List<Int>, value: ByteArray): ByteArray =
        (tag + value.size).map { it.toByte() }.toByteArray() + value

    /** Display ICCID -> BCD bytes (nibble-swapped, `F`-padded to an even length). */
    private fun iccidToBcd(display: String): ByteArray {
        val padded = if (display.length % 2 == 0) display else display + "F"
        return ByteArray(padded.length / 2) { i ->
            val low = padded[i * 2].digitToInt(16)
            val high = padded[i * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
