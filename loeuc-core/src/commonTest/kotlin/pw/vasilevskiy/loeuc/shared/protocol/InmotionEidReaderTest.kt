package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmotionEidReaderTest {

    @Test
    fun firstStepIsTheFirstWakeFrame() {
        val reader = InmotionEidReader()
        // openDownloadMode command 1: AA AA 16 06 01 2F 52 01 00 01 6C.
        assertEquals("aaaa1606012f520100016c", reader.start().hex())
    }

    @Test
    fun wakeAnswersMustComeFromTheAddressEachCommandTargets() {
        val reader = InmotionEidReader()
        reader.start() // wake 1 -> target 0x2F
        // The 0x21 answer belongs to wake 2, not wake 1: it must not advance wake 1.
        assertTrue(reader.onNotify(answerFrom(0x21)) is InmotionEidAction.NeedMore)
        assertTrue(reader.onNotify(answerFrom(0x2F)) is InmotionEidAction.Send) // -> wake 2 (target 0x21)
        // Now wake 2 wants the 0x21 answer, not another 0x2F.
        assertTrue(reader.onNotify(answerFrom(0x2F)) is InmotionEidAction.NeedMore)
        assertTrue(reader.onNotify(answerFrom(0x21)) is InmotionEidAction.Send) // -> wake 3 (target 0x23)
        assertTrue(reader.onNotify(answerFrom(0x23)) is InmotionEidAction.Send) // -> ATE0
    }

    @Test
    fun aWakeStepAdvancesOnlyOnAnAnswerFromTheTbox() {
        val reader = InmotionEidReader()
        reader.start()
        // Ordinary BMS many-to-many traffic (source 0x24) must not advance the wake step.
        val bmsFrame = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, 0x05, 0x24, 0x02, 0x81.toByte(), 0x00, 0x00, 0x00)
        assertTrue(reader.onNotify(bmsFrame) is InmotionEidAction.NeedMore)
        // Wake 1 targets the TBox (0x2F); only its answer (source 0x2F) advances to wake 2.
        val next = reader.onNotify(answerFrom(0x2F))
        assertTrue(next is InmotionEidAction.Send)
        // openDownloadMode command 2: AA AA 16 05 01 21 60 4A 01 18.
        assertEquals("aaaa16050121604a0118", (next as InmotionEidAction.Send).bytes.hex())
    }

    @Test
    fun walksWakeThenGetEidAndParsesTheEid() {
        val reader = InmotionEidReader()
        reader.start()
        // Three wake frames, each answered by the node it targets: 0x2F, 0x21, 0x23.
        assertTrue(reader.onNotify(answerFrom(0x2F)) is InmotionEidAction.Send) // -> wake 2
        assertTrue(reader.onNotify(answerFrom(0x21)) is InmotionEidAction.Send) // -> wake 3
        val afterWake3 = reader.onNotify(answerFrom(0x23))
        assertTrue(afterWake3 is InmotionEidAction.Send)
        assertEquals("ATE0\r\n", (afterWake3 as InmotionEidAction.Send).bytes.decodeToString()) // -> ATE0 #1

        // Five ATE0, each answered by OK; the fifth OK sends the first AT+CSIM.
        var last: InmotionEidAction = afterWake3
        repeat(5) { last = reader.onNotify("OK\r\n".encodeToByteArray()) }
        assertTrue(last is InmotionEidAction.Send)
        assertEquals(
            "AT+CSIM=10,\"0070000001\"\r\n",
            (last as InmotionEidAction.Send).bytes.decodeToString(),
        )

        // Open-channel and SELECT answers.
        assertTrue(reader.onNotify("+CSIM: 6,\"019000\"\r\nOK\r\n".encodeToByteArray()) is InmotionEidAction.Send)
        val afterSelect = reader.onNotify("+CSIM: 4,\"9000\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(afterSelect is InmotionEidAction.Send)
        assertEquals(
            "AT+CSIM=22,\"81E2910006BF3E035C015A\"\r\n",
            (afterSelect as InmotionEidAction.Send).bytes.decodeToString(),
        )

        // GetEID answer carries the EID; then the GetProfilesInfo step goes out.
        val eid = "89049032000000000000000000000012"
        val afterGetEid = reader.onNotify("+CSIM: 44,\"BF3E125A10${eid}9000\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(afterGetEid is InmotionEidAction.Send)
        assertEquals(eid, reader.eid)
        assertEquals(
            "AT+CSIM=16,\"81E2910003BF2D00\"\r\n",
            (afterGetEid as InmotionEidAction.Send).bytes.decodeToString(),
        )

        // GetProfilesInfo answer: one enabled business profile (BF2D -> A0 -> E3{5A,9F70,95}).
        // ICCID 89440000000007849349 stored BCD nibble-swapped, state 01, class 02.
        val profileValue = "BF2D17A015E3135A0A984400000000704839949F700101950102"
        val afterProfiles = reader.onNotify("+CSIM: 56,\"${profileValue}9000\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(afterProfiles is InmotionEidAction.Send)
        assertEquals(1, reader.profiles.size)
        assertEquals("89440000000007849349", reader.profiles.single().iccid)
        assertTrue(reader.profiles.single().enabled)
        assertTrue(reader.profiles.single().isBusiness)

        // Six network-status queries, then the close-channel step and Done.
        driveNetworkStatusSteps(reader)
        val done = reader.onNotify("+CSIM: 4,\"9000\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(done is InmotionEidAction.Done)
        assertEquals(eid, (done as InmotionEidAction.Done).eid)
        assertTrue(reader.finished)
    }

    @Test
    fun finishesWithNullEidWhenTheGetEidStepReturnsAnError() {
        val reader = InmotionEidReader()
        reader.start()
        reader.onNotify(answerFrom(0x2F)) // wake 1
        reader.onNotify(answerFrom(0x21)) // wake 2
        reader.onNotify(answerFrom(0x23)) // wake 3
        repeat(5) { reader.onNotify("OK\r\n".encodeToByteArray()) } // ATE0 -> first CSIM
        reader.onNotify("+CSIM: 6,\"019000\"\r\nOK\r\n".encodeToByteArray()) // open channel
        reader.onNotify("+CSIM: 4,\"9000\"\r\nOK\r\n".encodeToByteArray()) // select
        reader.onNotify("+CSIM: 4,\"6A88\"\r\nOK\r\n".encodeToByteArray()) // GetEID error, no TLV
        reader.onNotify("+CSIM: 4,\"6A88\"\r\nOK\r\n".encodeToByteArray()) // GetProfilesInfo error, no profiles
        driveNetworkStatusSteps(reader) // six network-status queries
        val done = reader.onNotify("+CSIM: 4,\"9000\"\r\nOK\r\n".encodeToByteArray()) // close channel
        assertTrue(done is InmotionEidAction.Done)
        assertNull((done as InmotionEidAction.Done).eid)
        assertNull(reader.eid)
        assertTrue(reader.profiles.isEmpty())
    }

    @Test
    fun getProfilesInfoStitchesA61xxGetResponseBeforeParsing() {
        val reader = InmotionEidReader()
        reader.start()
        reader.onNotify(answerFrom(0x2F))
        reader.onNotify(answerFrom(0x21))
        reader.onNotify(answerFrom(0x23))
        repeat(5) { reader.onNotify("OK\r\n".encodeToByteArray()) }
        reader.onNotify("+CSIM: 6,\"019000\"\r\nOK\r\n".encodeToByteArray()) // open channel
        reader.onNotify("+CSIM: 4,\"9000\"\r\nOK\r\n".encodeToByteArray()) // select
        val eid = "89049032000000000000000000000012"
        reader.onNotify("+CSIM: 44,\"BF3E125A10${eid}9000\"\r\nOK\r\n".encodeToByteArray()) // GetEID -> profiles step

        // The STORE DATA answers 61 08 ("8 more bytes"); the reader must send GET RESPONSE with the
        // channel-only CLA and Le = 08, not advance yet.
        val getResponse = reader.onNotify("+CSIM: 4,\"6108\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(getResponse is InmotionEidAction.Send)
        assertEquals(
            "AT+CSIM=10,\"01C0000008\"\r\n",
            (getResponse as InmotionEidAction.Send).bytes.decodeToString(),
        )
        assertTrue(reader.profiles.isEmpty()) // not parsed until 90 00

        // GET RESPONSE returns the BF2D body with 90 00; now the profile is parsed and the reader
        // advances to close the channel.
        val profileValue = "BF2D17A015E3135A0A984400000000704839949F700101950102"
        val afterProfiles = reader.onNotify("+CSIM: 56,\"${profileValue}9000\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(afterProfiles is InmotionEidAction.Send)
        assertEquals("89440000000007849349", reader.profiles.single().iccid)
        assertTrue(reader.profiles.single().enabled)
    }

    @Test
    fun skipAdvancesPastSilentStepsAndStillReachesDoneKeepingTheEid() {
        val reader = InmotionEidReader()
        reader.start()
        reader.onNotify(answerFrom(0x2F))
        reader.onNotify(answerFrom(0x21))
        reader.onNotify(answerFrom(0x23))
        repeat(5) { reader.onNotify("OK\r\n".encodeToByteArray()) }
        reader.onNotify("+CSIM: 6,\"019000\"\r\nOK\r\n".encodeToByteArray()) // open channel
        reader.onNotify("+CSIM: 4,\"9000\"\r\nOK\r\n".encodeToByteArray()) // select
        val eid = "89049032000000000000000000000012"
        reader.onNotify("+CSIM: 44,\"BF3E125A10${eid}9000\"\r\nOK\r\n".encodeToByteArray()) // -> profiles step

        // Nothing from here on answers: skip() must keep advancing through the profiles and the six
        // network-status steps to the close step and Done, never stranding, and the EID must survive.
        var action: InmotionEidAction = reader.skip()
        var guard = 0
        while (action is InmotionEidAction.Send && guard++ < 20) {
            action = reader.skip()
        }
        assertTrue(action is InmotionEidAction.Done)
        assertEquals(eid, (action as InmotionEidAction.Done).eid)
        assertTrue(reader.finished)
    }

    @Test
    fun aCsimStepIgnoresDataErrorNoiseAndWaitsForItsOwnCsimLine() {
        val reader = InmotionEidReader()
        reader.start()
        reader.onNotify(answerFrom(0x2F))
        reader.onNotify(answerFrom(0x21))
        reader.onNotify(answerFrom(0x23))
        repeat(5) { reader.onNotify("OK\r\n".encodeToByteArray()) } // ATE0 -> first CSIM step

        // The module streams "data error" and bare OK between commands: none of it may complete
        // the CSIM step - only a line with "+CSIM:" followed by OK does.
        assertTrue(reader.onNotify("data error".encodeToByteArray()) is InmotionEidAction.NeedMore)
        assertTrue(reader.onNotify("\r\nOK\r\n".encodeToByteArray()) is InmotionEidAction.NeedMore)
        assertTrue(reader.onNotify("data errordata error".encodeToByteArray()) is InmotionEidAction.NeedMore)
        val advanced = reader.onNotify("+CSIM: 6,\"019000\"\r\nOK\r\n".encodeToByteArray())
        assertTrue(advanced is InmotionEidAction.Send)
    }

    /** Feed answers to the six network-status queries; each returns a Send for the next step. */
    private fun driveNetworkStatusSteps(reader: InmotionEidReader) {
        listOf(
            "+CSQ: 15,99",
            "+CREG: 0,3",
            "+CEREG: 0,3",
            "+COPS: 0",
            "+CGATT: 0",
            "+CEER: No Service",
        ).forEach { line ->
            assertTrue(reader.onNotify("$line\r\nOK\r\n".encodeToByteArray()) is InmotionEidAction.Send)
        }
    }

    /** A minimal many-to-many frame from [source] to the host (0x01): a wake answer. */
    private fun answerFrom(source: Int): ByteArray =
        byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x16, 0x05, source.toByte(), 0x01, 0xD2.toByte(), 0x00, 0x00, 0x00)

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
