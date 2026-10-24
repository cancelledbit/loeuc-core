package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmotionTboxTest {

    @OptIn(ExperimentalWriteApi::class)
    @Test
    fun rebootCommandMatchesTheApp() {
        assertEquals("AA AA 16 06 01 2F 4F 01 00 01 71", InmotionTbox.rebootCommand().toHex().uppercase())
        val frame = InmotionTbox.rebootCommand()
        val xor = frame.copyOfRange(2, frame.size - 1).fold(0) { a, b -> a xor (b.toInt() and 0xFF) }
        assertEquals(frame.last().toInt() and 0xFF, xor)
    }

    @OptIn(ExperimentalWriteApi::class)
    @Test
    fun downloadModeCommandsMatchTheAppAndAreWellFramed() {
        val open = InmotionTbox.openDownloadModeCommands()
        assertEquals(
            listOf(
                "AA AA 16 06 01 2F 52 01 00 01 6C",
                "AA AA 16 05 01 21 60 4A 01 18",
                "AA AA 16 04 01 23 50 01 61",
                "41 54 45 30 0D 0A", // ATE0\r\n
                "41 54 45 30 0D 0A",
                "41 54 45 30 0D 0A",
                "41 54 45 30 0D 0A",
                "41 54 45 30 0D 0A",
            ),
            open.map { it.toHex().uppercase() },
        )
        val close = InmotionTbox.closeDownloadModeCommands()
        assertEquals(
            listOf(
                "AA AA 16 04 01 23 50 00 60",
                "AA AA 16 05 01 21 60 4A 00 19",
                "AA AA 16 06 01 2F 52 01 00 00 6D",
            ),
            close.map { it.toHex().uppercase() },
        )
        // Every Lorin frame's last byte is the XOR of everything after the two AA markers.
        (open + close).filter { it.size >= 4 && it[0] == 0xAA.toByte() }.forEach { frame ->
            val xor = frame.copyOfRange(2, frame.size - 1).fold(0) { a, b -> a xor (b.toInt() and 0xFF) }
            assertEquals(frame.last().toInt() and 0xFF, xor)
        }
    }

    @Test
    fun esimListCommandMatchesTheAppFrame() {
        // AA AA 16 03 02 2F 10 <xor>, the getTBoxEsimList request the official app sends:
        // host source 0x02, TBox target 0x2F, command 0x10 (bit 0x80 clear = read).
        val expected = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0x16, 0x03, 0x02, 0x2F, 0x10, 0x28,
        )
        assertEquals(expected.toHex(), InmotionTbox.esimListCommand().toHex())
    }

    @Test
    fun statusReadCommandsMatchTheTboxRealtimeNetworkAndFixedReads() {
        assertEquals(
            listOf(
                "AA AA 16 03 01 2F 01 3A", // getTBoxRealtimeInfo (cmd 0x01)
                "AA AA 16 03 01 2F 02 39", // getTBoxNetworkConfig (cmd 0x02)
                "AA AA 16 03 01 2F 04 3F", // getTBoxFixedInfo (cmd 0x04)
            ),
            InmotionTbox.statusReadCommands().map { it.toHex().uppercase() },
        )
    }

    @Test
    fun imeiIsExtractedFromAFixedInfoAnswerAtPayloadOffset14() {
        val imei = "356938035643809"
        // AA AA 16 <len> 2F 01 84 <14 bytes><imei ASCII><pad>; only offset/cmd/source matter.
        val payload = ByteArray(14) { 0 } + imei.encodeToByteArray() + ByteArray(60 - 14 - imei.length)
        val body = byteArrayOf(0x2F, 0x01, 0x84.toByte()) + payload
        val frame = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, body.size.toByte()) + body + byteArrayOf(0x00)
        assertEquals(imei, InmotionTbox.imeiFromFixedInfo(frame))
        // A network-config answer (cmd 0x02) is not a fixed-info answer.
        val other = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, 0x05, 0x2F, 0x01, 0x82.toByte(), 0x00, 0x00, 0x00)
        assertNull(InmotionTbox.imeiFromFixedInfo(other))
    }

    @Test
    fun userInfoIsExtractedFromANetworkConfigAnswer() {
        // user_name at offset 4, pwd at offset 37 (both ASCII, NUL-padded). The windows overlap
        // (4:74 and 37:140), so the decode must stop at the first NUL - otherwise user_name would be
        // glued to pwd, which the backend rejects as "invalid params".
        val payload = ByteArray(140)
        "TBOXUSER01".encodeToByteArray().copyInto(payload, 4)
        "OOVoIQtQsxRGMnyx".encodeToByteArray().copyInto(payload, 37)
        val body = byteArrayOf(0x2F, 0x01, 0x82.toByte()) + payload
        val frame = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, body.size.toByte()) + body + byteArrayOf(0x00)
        assertEquals("TBOXUSER01" to "OOVoIQtQsxRGMnyx", InmotionTbox.userInfoFromNetworkConfig(frame))
        // A fixed-info answer (cmd 0x04) is not a network-config answer.
        val fixed = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, 0x05, 0x2F, 0x01, 0x84.toByte(), 0, 0, 0)
        assertNull(InmotionTbox.userInfoFromNetworkConfig(fixed))
    }

    @Test
    fun userInfoStopsAtNulAndDropsStrayBytesSoTheWindowsDoNotGlue() {
        // Real TBox fields carry a stray high byte right after the value (a 0xD3 seen on hardware),
        // and the user_name/pwd windows overlap (4:74 and 37:140). The decode must stop at the first
        // NUL and keep only printable ASCII, so user_name stays the clean SN (it does NOT run on into
        // the pwd field) and pwd stays clean. Feeding the glued, stray-byte value instead makes the
        // /v2/imei/check backend answer "invalid params" (error 30001).
        val payload = ByteArray(140)
        // user_name = "SN01" + 0xD3 stray byte, then NUL padding.
        byteArrayOf('S'.code.toByte(), 'N'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 0xD3.toByte())
            .copyInto(payload, 4)
        // pwd = "OOVoIQtQsxRGMnyx" + 0xD3 stray byte, then NUL padding.
        ("OOVoIQtQsxRGMnyx".encodeToByteArray() + byteArrayOf(0xD3.toByte())).copyInto(payload, 37)
        val body = byteArrayOf(0x2F, 0x01, 0x82.toByte()) + payload
        val frame = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, body.size.toByte()) + body + byteArrayOf(0x00)
        // Stray 0xD3 dropped, NUL stops the field, user_name does not absorb the pwd.
        assertEquals("SN01" to "OOVoIQtQsxRGMnyx", InmotionTbox.userInfoFromNetworkConfig(frame))
    }

    @Test
    fun manyToManyAnswerFromTheTboxIsRecognised() {
        val frame = tboxFrame("89049032000000000000000000000012".encodeToByteArray())
        assertTrue(InmotionTbox.isEsimResponseFrame(frame))
    }

    @Test
    fun anUnrelatedBmsAnswerIsNotAnEsimResponse() {
        // Source 0x24 is a BMS, not the TBox; command byte 0x81 is not the eSIM command.
        val frame = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0x16, 0x05, 0x24, 0x02, 0x81.toByte(), 0x00, 0x00, 0x00,
        )
        assertFalse(InmotionTbox.isEsimResponseFrame(frame))
    }

    @Test
    fun esimResponseExposesRawPayloadAndEidCandidate() {
        val eid = "89049032000000000000000000000012"
        val response = InmotionTbox.esimResponse(tboxFrame(eid.encodeToByteArray()))
        assertTrue(response != null)
        assertEquals(eid, response.payload.decodeToString())
        assertTrue(response.printableStrings.any { it.contains(eid) })
        assertEquals(eid, response.eidCandidate)
    }

    @Test
    fun esimResponseSurvivesAShortOrGarbageFrame() {
        // Well-formed envelope from the TBox but with no payload at all: no throw, empty payload.
        val frame = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0x16, 0x03, 0x2F, 0x02, 0x90.toByte(), 0x00,
        )
        val response = InmotionTbox.esimResponse(frame)
        assertTrue(response != null)
        assertEquals(0, response.payload.size)
        assertNull(response.eidCandidate)
    }

    /** `AA AA 16 <len> 2F 02 90 <payload> <xor>`: a TBox answer to the host. */
    private fun tboxFrame(payload: ByteArray): ByteArray {
        val body = byteArrayOf(0x2F, 0x02, 0x90.toByte()) + payload
        val header = byteArrayOf(0x16, body.size.toByte()) + body
        val checksum = header.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + header + checksum
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
