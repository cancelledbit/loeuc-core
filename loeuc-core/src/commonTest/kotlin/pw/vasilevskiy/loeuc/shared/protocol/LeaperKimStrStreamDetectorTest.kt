package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are verbatim slices of the two real LeaperKim captures from 2026-08-10,
 * `loeuc_882583F62376_unknown_20260810_172750.jsonl` and `..._173609.jsonl`.
 */
class LeaperKimStrStreamDetectorTest {
    @Test
    fun reportsIdleWithoutBytes() {
        val detector = LeaperKimStrStreamDetector()

        val state = detector.state(nowMillis = 1_000)

        assertEquals(LeaperKimStrTransport.Idle, state.transport)
        assertEquals(0, state.bytesInWindow)
        assertNull(state.headerWeekday)
    }

    @Test
    fun recognisesFramedDiagnosticStream() {
        val detector = LeaperKimStrStreamDetector()

        FramedCapture.feedTo(detector, startMillis = 1_000)
        val state = detector.state(nowMillis = 1_100)

        assertEquals(LeaperKimStrTransport.Framed, state.transport)
        assertEquals(3, state.strPacketCount)
        assertEquals(1, state.headerWeekday)
    }

    @Test
    fun readsRecordTextAcrossPacketHeaders() {
        val detector = LeaperKimStrStreamDetector()

        FramedCapture.feedTo(detector, startMillis = 1_000)
        val state = detector.state(nowMillis = 1_100)

        // The record start sits right after the first packet header, and the next two headers land
        // mid-record, so the page selector is only readable once headers are stripped.
        assertEquals(1, state.recordCount)
    }

    @Test
    fun recognisesUnframedDiagnosticText() {
        val detector = LeaperKimStrStreamDetector()

        RawTextCapture.feedTo(detector, startMillis = 1_000)
        val state = detector.state(nowMillis = 1_100)

        assertEquals(LeaperKimStrTransport.RawText, state.transport)
        assertEquals(0, state.strPacketCount)
        assertEquals(1, state.recordCount)
        assertEquals(1, state.headerWeekday)
    }

    @Test
    fun recognisesOrdinaryTelemetryFrames() {
        val detector = LeaperKimStrStreamDetector()

        NormalFrameCapture.feedTo(detector, startMillis = 1_000)
        val state = detector.state(nowMillis = 1_100)

        assertEquals(LeaperKimStrTransport.NormalFrames, state.transport)
        assertEquals(0, state.recordCount)
        assertNull(state.headerWeekday)
    }

    @Test
    fun doesNotClaimToUnderstandTheUnidentifiedTail() {
        val detector = LeaperKimStrStreamDetector()

        UnknownTailCapture.feedTo(detector, startMillis = 1_000)
        val state = detector.state(nowMillis = 1_100)

        assertEquals(LeaperKimStrTransport.Unknown, state.transport)
        assertEquals(0, state.strPacketCount)
    }

    @Test
    fun singlePacketHeaderIsNotEnoughForFramed() {
        val detector = LeaperKimStrStreamDetector()
        val onePacket = FramedCapture.copyOfRange(0, LeaperKimStrStreamDetector.StrPacketLength)

        detector.ingest(onePacket, timestampMillis = 1_000)
        val state = detector.state(nowMillis = 1_050)

        assertEquals(1, state.strPacketCount)
        assertTrue(state.transport != LeaperKimStrTransport.Framed)
    }

    @Test
    fun headersSpacedWrongAreNotFramed() {
        val detector = LeaperKimStrStreamDetector()
        val header = LeaperKimStrStreamDetector.StrPacketHeader
        val stuffed = ByteArray(120) { 0x41 }
        header.copyInto(stuffed, 0)
        header.copyInto(stuffed, 40)
        header.copyInto(stuffed, 90)

        detector.ingest(stuffed, timestampMillis = 1_000)
        val state = detector.state(nowMillis = 1_050)

        assertEquals(3, state.strPacketCount)
        assertEquals(LeaperKimStrTransport.Unknown, state.transport)
    }

    @Test
    fun readsTheHeaderWeekday() {
        val detector = LeaperKimStrStreamDetector()
        val onTuesday = RawTextCapture.copyOf()
        onTuesday[RawTextWeekdayDigitOffset] = '2'.code.toByte()

        detector.ingest(onTuesday, timestampMillis = 1_000)
        val state = detector.state(nowMillis = 1_050)

        assertEquals(2, state.headerWeekday)
    }

    @Test
    fun dropsBytesThatFallOutOfTheWindow() {
        val detector = LeaperKimStrStreamDetector(windowMillis = 1_000)

        FramedCapture.feedTo(detector, startMillis = 1_000)

        assertEquals(LeaperKimStrTransport.Framed, detector.state(nowMillis = 1_500).transport)
        assertEquals(LeaperKimStrTransport.Idle, detector.state(nowMillis = 9_000).transport)
    }

    @Test
    fun capsRetainedBytes() {
        val detector = LeaperKimStrStreamDetector(maxWindowBytes = 100)

        repeat(20) { index ->
            detector.ingest(ByteArray(20) { 0x41 }, timestampMillis = 1_000L + index)
        }

        assertTrue(detector.state(nowMillis = 1_100).bytesInWindow <= 120)
    }

    @Test
    fun resetClearsTheWindow() {
        val detector = LeaperKimStrStreamDetector()

        FramedCapture.feedTo(detector, startMillis = 1_000)
        detector.reset()

        assertEquals(LeaperKimStrTransport.Idle, detector.state(nowMillis = 1_100).transport)
    }
}

/** Splits the fixture the way Android delivers it: one notify callback per 20-byte chunk. */
private fun ByteArray.feedTo(detector: LeaperKimStrStreamDetector, startMillis: Long) {
    var offset = 0
    var millis = startMillis
    while (offset < size) {
        val end = minOf(offset + 20, size)
        detector.ingest(copyOfRange(offset, end), millis)
        offset = end
        millis++
    }
}

/** Three consecutive 70-byte `StR` packets, mode byte at 3. */
private val FramedCapture = (
    "5374527F00400D0A0D0A323032362D30382D31302032323A32373A32332F3120" +
        "20303A30303A3435203134322E303756203134362E3938567070203134312E38" +
        "34566E7020315374527F004034372E3035566D78203134312E3633566D692020" +
        "33302E3139436D6F202034312E3335436D742038313132332E35567020363030" +
        "30313E494D2036303030313E5374527F0040467320202020302E304141202020" +
        "20312E3041422020202D302E354143202020332E3741502020202035303E5651" +
        "2020202D31323E564420202035303E567164"
    ).decodeHex()

/** The same record with no packet headers, as emitted while the mode byte is at most 2. */
private val RawTextCapture = (
    "0D0A0D0A323032362D30382D31302032323A32373A32312F312020303A30303A" +
        "3433203134322E303756203134362E3731567070203134312E3839566E702031" +
        "34372E3035566D78203134312E3633566D69202033302E3136436D6F20203431" +
        "2E3334436D742038313132332E3456702036303030313E494D2036303030313E" +
        "467320202020302E30414120202020302E3741422020202D302E374143202020" +
        "332E3641502020202034363E56512020202D31313E564420202034373E567164" +
        "2020202036313E4F"
    ).decodeHex()

/** `\r\n\r\n` plus the 19-character timestamp plus `/`. */
private const val RawTextWeekdayDigitOffset = 4 + 19 + 1

/** Two chained `DC 5A 5C` frames, declared lengths 83 and 95. */
private val NormalFrameCapture = (
    "DC5A5C53377F0000DA7D00006B530003FFF60CF4037F000003C002EE232C0078" +
        "FFFA010480C80000808080808080022801005180800F6C0F6C0F660F6F0F6F0F" +
        "6F0F6F0F6E0F6A0F700F700F710F700F710F6DFBE1AF44DC5A5C5F377A0000DA" +
        "7D00006B530003FFF50CF3037F000003C002EE232C0078FFF9011180C8000080" +
        "808080808003099E09A409AB0000000000000F6F0F6F0F6F0F700F6F0F6B0000" +
        "00000000000000000000000000000000000000000000B82C559B"
    ).decodeHex()

/** From the run that both captures ended in. No known producer emits this shape. */
private val UnknownTailCapture = (
    "8500B48DF80CF408B4A5A00400406F840C0020818C848C180CF80C8400C0AC01" +
        "E18C04A000C66984840410B8B509F184040011A2CA8CE408B447CA844400B88D" +
        "20049089D0FD5FCA0CC5017B4CF40CC50181BD48FA8484003D10FF04C4008D04" +
        "8404CDBD08FF0400BC11F98496801588FE848600D701FE84070198883103F404" +
        "0C00CE4A8484E808881080405FC404004029F184A500B10AF884000096CA0400" +
        "784EFC1400A9C810102401FC1000D45DCA0401889DA0840520CE5DC4100800BD" +
        "11FA0400B805F800"
    ).decodeHex()
