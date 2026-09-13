package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WheelRawSignaturesTest {

    @Test
    fun begodeIsRecognisedWithinTheFirstTwoNotifiesOfARecordedConnection() {
        // The first notify opens a frame whose trailer lies in the next one; that is not enough on
        // its own, and the second notify - the boundary - is.
        listOf(BegodeAlignedStream, BegodeEtMax).forEach { stream ->
            assertNull(WheelRawSignatures.detectFamily(stream[0].decodeHex()))
            assertEquals(WheelRawFamily.Begode, WheelRawSignatures.detectFamily(stream[1].decodeHex()))
        }
    }

    @Test
    fun begodeHeaderWithItsTrailerOutsideTheNotifyDoesNotMatch() {
        // The review case: `55 AA` twenty bytes before the end of a foreign notify with a common
        // type/page pair after it. Before the fix this alone moved an InMotion V2 ride to Begode.
        val foreign = "aaaa1122334455aa000000000000000000000000000000000100".decodeHex()
        assertFalse(WheelRawSignatures.matchesBegode(foreign))
        assertFalse(WheelRawSignatures.matchesBegode(BegodeAlignedStream[0].decodeHex()))
    }

    @Test
    fun begodeNotifyEndingAFrameMatches() {
        // Type 7 on page 24 and the whole trailer as the last six bytes.
        assertTrue(WheelRawSignatures.matchesBegode("0000001c0001000000000000000007185a5a5a5a".decodeHex()))
        // An eight-byte closing notify of a 20 + 20 + 8 wheel carries the same shape.
        assertTrue(WheelRawSignatures.matchesBegode("00c604185a5a5a5a".decodeHex()))
        // The same tail with an unknown pair does not.
        assertFalse(WheelRawSignatures.matchesBegode("0000001c0001000000000000000005185a5a5a5a".decodeHex()))
    }

    @Test
    fun begodeBoundaryNotifiesMatchWhereverTheFrameStarts() {
        // `5A 5A 5A 5A 55 AA` with the header at offsets 4, 8, 12 and 16 of a drifting stream.
        listOf(BegodeDrifting[2], BegodeDrifting[3], BegodeDrifting[4], BegodeEtMax[4]).forEach { hex ->
            assertTrue(WheelRawSignatures.matchesBegode(hex.decodeHex()), hex)
        }
    }

    @Test
    fun begodeNotifyWithoutAHeaderDoesNotMatch() {
        // A frame's middle only: neither header nor a type/page pair before the trailer.
        assertFalse(WheelRawSignatures.matchesBegode("1bed00c80001000000120418000000000000ffea".decodeHex()))
    }

    @Test
    fun begodeHeaderWithAnUnknownTypePageOrABrokenTrailerDoesNotMatch() {
        val frame = BegodeAlignedStream[0].decodeHex()
        // Type 0 belongs on page 24 only.
        assertFalse(WheelRawSignatures.matchesBegode(frame.copyOf().also { it[19] = 0x05 }))
        // Type 5 is not a frame type.
        assertFalse(WheelRawSignatures.matchesBegode(frame.copyOf().also { it[18] = 0x05 }))
        // The same header with a 24-byte notify must carry the trailer.
        val whole = frame + "5a5a5a5a".decodeHex()
        assertTrue(WheelRawSignatures.matchesBegode(whole))
        assertFalse(WheelRawSignatures.matchesBegode(whole.copyOf().also { it[22] = 0x00 }))
    }

    @Test
    fun kingSongIsRecognisedOnItsLiveAndBmsFrames() {
        KingSongS22.filterIndexed { index, _ -> index != NameFrameIndex && index != SerialFrameIndex }
            .forEach { hex ->
                assertEquals(WheelRawFamily.KingSong, WheelRawSignatures.detectFamily(hex.decodeHex()), hex)
            }
    }

    @Test
    fun kingSongTextAnswersAndUnknownCommandsDoNotMatch() {
        assertFalse(WheelRawSignatures.matchesKingSong(KingSongS22[NameFrameIndex].decodeHex()))
        assertFalse(WheelRawSignatures.matchesKingSong(KingSongS22[SerialFrameIndex].decodeHex()))
        val live = KingSongS22[3].decodeHex()
        assertFalse(WheelRawSignatures.matchesKingSong(live.copyOf().also { it[16] = 0x18 }))
        assertFalse(WheelRawSignatures.matchesKingSong(live.copyOf().also { it[19] = 0x00 }))
    }

    @Test
    fun leaperKimAndNosfetFramesMatchTheSameFamily() {
        listOf(LeaperKimLk5690[0], LeaperKimLk17344[0], LeaperKimShermanL[0], NosfetNf0126[0]).forEach { hex ->
            assertEquals(WheelRawFamily.LeaperKim, WheelRawSignatures.detectFamily(hex.decodeHex()), hex)
        }
    }

    @Test
    fun leaperKimContinuationDiagnosticsAndImplausibleLengthsDoNotMatch() {
        // Frame tails, the plugin page, the `StR` text and the 500 Hz co-stream.
        (LeaperKimLk17344.drop(1) + NosfetNf0126.drop(1) + LeaperKimDiagnostics).forEach { hex ->
            assertNull(WheelRawSignatures.detectFamily(hex.decodeHex()), hex)
        }
        val frame = LeaperKimLk5690[0].decodeHex()
        assertFalse(WheelRawSignatures.matchesLeaperKim(frame.copyOf().also { it[3] = 0x02 }))
        assertFalse(WheelRawSignatures.matchesLeaperKim(frame.copyOf(3)))
    }

    /**
     * Every recorded notify of every brand gets at most its own family. A single cross-brand
     * match here is a live connection moved to the wrong decoder.
     */
    @Test
    fun noNotifyMatchesAnotherBrandsFamily() {
        val corpus = mapOf(
            WheelRawFamily.Begode to BegodeAlignedStream + BegodeDrifting + BegodeEtMax,
            WheelRawFamily.KingSong to KingSongS22,
            WheelRawFamily.LeaperKim to LeaperKimLk5690 + LeaperKimLk17344 + LeaperKimShermanL + NosfetNf0126 +
                LeaperKimDiagnostics,
            null to NotOurFamilies,
        )
        var ownMatches = 0
        corpus.forEach { (family, notifies) ->
            notifies.forEach { hex ->
                val detected = WheelRawSignatures.detectFamily(hex.decodeHex())
                if (detected != null) {
                    assertEquals(family, detected, hex)
                    ownMatches++
                }
            }
        }
        // Not vacuous: 12 Begode, 10 KingSong and 4 LeaperKim/NOSFET notifies carry a signature.
        assertEquals(26, ownMatches)
    }

    private companion object {
        const val NameFrameIndex = 11
        const val SerialFrameIndex = 8

        /** GotWay_45245: a frame opening a notify, then the boundary notify. */
        val BegodeAlignedStream = listOf(
            "55aa19920000000000000154f74c0001004c0018",
            "5a5a5a5a55aa016a72c428801c1a003c00000000",
        )

        /** GotWay_006977, first eight notifies: frames drifting across notify boundaries. */
        val BegodeDrifting = listOf(
            "0000001c0001000000000000000007185a5a5a5a",
            "55aa1509000000160002012cf9e3108900080018",
            "5a5a5a5a55aa005a00000435000000190018021b",
            "7d0101035a5a5a5a55aa0d270d310d2e0d350d31",
            "0d2e0d310d3503035a5a5a5a55aa000556684000",
            "1bc8003c00000000001204185a5a5a5a55aaffd1",
            "0000001c0001000000000000000007185a5a5a5a",
            "55aa15090000001600020172f9e1108900080018",
        )

        /** GotWay_74679 (ET Max), first six notifies. */
        val BegodeEtMax = listOf(
            "55aa181c000000601db10000fb1314a900090018",
            "5a5a5a5a55aa0055ffd706070000000000000000",
            "000001025a5a5a5a55aa00000000000000000000",
            "00000000000002005a5a5a5a55aa003f7b4d4800",
            "1bed00c800010000001204185a5a5a5a55aaffea",
            "000000140000000000000000000007185a5a5a5a",
        )

        /** KSN-S22-90270, first twelve notifies, name and serial answers included. */
        val KingSongS22 = listOf(
            "aa557017000002007e0011008f030000f6145a5a",
            "aa550000000000000000000000000000c9145a5a",
            "aa550000000000000000000000000000f4005a5a",
            "aa55d62a6e077900c5766c00f00a01e0a9145a5a",
            "aa5500009303e4045d0b120100006009b9145a5a",
            "aa5500003200370038003c0000000000b5145a5a",
            "aa550000000000000000000000003315f5145a5a",
            "aa557017000002007e0011008f030000f6145a5a",
            "aa554b5353413031423232303333304ab3303733",
            "aa550000000000000000000000000000c9145a5a",
            "aa550000000000000000000000000000f4015a5a",
            "aa554b532d5332322d30323337000000bb1484fd",
        )

        /** LK5690, a 36-byte frame (length byte `0x20`) split 20 + 16. */
        val LeaperKimLk5690 = listOf(
            "dc5a5c20242c00005d5f000142ac008bffe10fd5",
            "0dc700000af00246045300020070007e",
        )

        /** LK17344, a frame, its tail and the plugin page that follows it. */
        val LeaperKimLk17344 = listOf(
            "dc5a5c4530540000ee48001bee48001b00000ddc",
            "0d51000007d003200fb1000219ce0000006f0000",
            "80808080808004000335ffffffffff32eefd2109",
            "ab12c903d00000ffe0c78e511a",
        )

        /** LK8435 (Sherman-L). */
        val LeaperKimShermanL = listOf(
            "dc5a5c2f276700000b8a0000a6210228fffa1008",
            "09110000038407d00bc70003ffee0080006f0000",
            "000000000000076071a03c",
        )

        /** NF0126, one whole frame across five notifies. */
        val NosfetNf0126 = listOf(
            "dc5a5c5335f400009f620022a3b4003d00520f88",
            "049f000003e807d0a51007a1ff77016480c80000",
            "808080808080010001000580800f050f040f050f",
            "050f040f010eff0eff0f000f000f000efc0f020f",
            "030f03e4efdd98",
        )

        /** LK21571 in diagnostic mode: the co-stream and the `StR` text, no `DC 5A 5C` frame. */
        val LeaperKimDiagnostics = listOf(
            "f5f0f4f0f0f9f1f5f0f5f9f0f4f4f0f4f4400200",
            "000040e400000000000040f00040084018401440",
            "0000001a014814ac847c88b07e77ededff7f02a8",
            "807f7f2836ba0040cc007e7f7018047ef0f5f0f4",
            "807f7f2836c50040dc007e3c2ffd551cf0f9f0f4",
            "202020312e3741422020202d312e324143202020",
            "186923f1f5f0f4f4f0f5f0f4f4f9f5f8f4537452",
        )

        /** Brands with signatures of their own: InMotion V2, legacy InMotion, SKAT, HW charger, Xtreme. */
        val NotOurFamilies = listOf(
            "aaaa11088201020d0101010094",
            "aaaa141185fa5800000000010011590000000001006a",
            "aaaa1401a5550f8500000000000000fe02010006",
            "aaaa0703a5550f01000000000000000402000075",
            "fafe192543000020417e60ce4400005f43e7dcf7406de9d744",
            "300600605f430000000000c2474200808a410000c041110411430000000000000000000000000000000000000000000109",
            "2030303030302c2030303535342c203030303031",
        )
    }
}
