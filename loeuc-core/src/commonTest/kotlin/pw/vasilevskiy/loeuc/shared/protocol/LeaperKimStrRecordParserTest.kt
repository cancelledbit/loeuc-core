package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every fixture here is a verbatim slice of a real capture from a Lynx S on firmware `0090.04`.
 * See `docs/leaperkim-str-debug-telemetry-ru.md` for what the captures are and how they were taken.
 */
class LeaperKimStrRecordParserTest {
    private val CleanCapture =
        "\r\n\r\n2026-08-11 17:52:28/2  0:00:30 140.88V 146.44Vpp 140.74Vnp 146.56Vmx 140.88Vmi  29.82Cmo" +
        "  32.68Cmt 81123.5Vp 60001>IM 60001>Fs   -6.4AA    0.2AB    6.2AC  21.9AP  3026>VQ   -69>VD 2853" +
        ">Vqd  3753>Out 2761>Bem   0>RA    7.4AQ   -0.2AD   1.7AI  858>ChgAD 0>CM   549>CdA    85>TC    8" +
        "2>FTC  1174>LDpp  1672>FdHz  398>dKM    0>MdKM   2>GC  0>CC 0>Lm1   0>U1   0>U2   0>U3   0>U4   " +
        "0>U5\r\n\r\n2026-08-11 17:52:28/2  0:00:31 142.02V 146.39Vpp 140.79Vnp 146.56Vmx 140.88Vmi  29.8" +
        "3Cmo  32.90Cmt 81123.5Vp 60001>IM 60001>Fs    9.2AA  -13.5AB    4.3AC  21.5AP  2276>VQ   162>VD " +
        "2726>Vqd  3586>Out 2652>Bem  -1>RA  -11.2AQ   -0.5AD  -2.6AI  854>ChgAD 0>CM   549>CdA  -123>TC " +
        " -126>FTC  1125>LDpp  1983>FdHz  471>dKM    0>MdKM   2>GC  0>CC 0>Lm1   0>U1   0>U2   0>U3   0>U" +
        "4   0>U5\r\n\r\n2026-08-11 17:52:29/2  0:00:31 141.45V 146.69Vpp 140.84Vnp 146.74Vmx 140.88Vmi  " +
        "29.78Cmo  35.13Cmt 81123.4Vp 60001>IM 60001>Fs   -1.2AA    0.7AB    0.5AC  21.0AP  1654>VQ    26" +
        ">VD 1746>Vqd  2297>Out 1666>Bem   0>RA   -1.1AQ    0.0AD   0.0AI  849>ChgAD 0>CM   544>CdA   -10" +
        ">TC    -9>FTC   707>LDpp  1421>FdHz  348>dKM    0>MdKM   2>GC  0>CC 0>Lm1   0>U1   0>U2   0>U3  " +
        " 0>U4   0>U5"

    private val HeaderlessPage =
        "\r\n\r\n 8100>Ha     0>Pole 100>rAB    0>di1    0>di2  300>Dif 300>HPS 865>Di  300>JA  -30>IA  2" +
        "70>FA 32768>AC 32768>BC 32768>CC  2513>ApS     0>BpS  8703>CpS 164725699>AD         0>BD 5704159" +
        "66>CD -1>Dir    -2>SDr   0>isH 183785416>DS    2>LDpp   16>6Dpp    0>HdHz   0>DerC   0>Her8   0>" +
        "dpOis   0>fyOis   0>diOis   0>U1   0>U2   0>U3   0>U4   0>U5\r\n\r\n"

    private val FramedCapture = (
        "0D0A0D0A323032362D30382D31302032323A32373A32352F312020303A30303A3437203134322E31335620313436" +
            "2E3931567070203134312E3936566E7020315374527F004034372E3035566D78203134312E3633566D6920203330" +
            "2E3231436D6F202034312E3335436D742038313132332E3556702036303030313E494D2036303030313E5374527F" +
            "0040467320202020302E30414120202020302E3741422020202D302E354143202020332E3441502020202034303E" +
            "56512020202D31333E564420202034363E5671645374527F00402020202036303E4F757420202020303E42656D20" +
            "2020303E524120202020302E36415120202020302E304144202020302E30414920203835353E4368674144205374" +
            "527F0040303E434D2020203534383E4364412020202020363E54432020202020353E465443202020202D323E4C44" +
            "70702020202020303E4664487A20202020303E644B4D5374527F004020202020303E4D644B4D202020303E474320" +
            "20303E434320303E4C6D31202020303E5531202020303E5532202020303E5533202020303E5534202020303E5535" +
            "5374527F00400D0A0D0A"
        ).decodeHex()

    private fun parseWhole(bytes: ByteArray): List<LeaperKimStrRecord> =
        LeaperKimStrRecordParser().ingest(bytes, 1_000L)

    /** The record delimiter as text, so the fixtures can be cut where the wheel cuts them. */
    private val RecordDelimiterText = "\r\n\r\n"

    private fun ascii(text: String): ByteArray =
        ByteArray(text.length) { (text[it].code and 0xFF).toByte() }

    @Test
    fun readsEveryFieldOfACompleteRecord() {
        val records = parseWhole(ascii(CleanCapture))

        // Three delimiters bound two complete records; the third opens a record we cannot close yet.
        assertEquals(2, records.size)
        val first = records.first()
        assertEquals(40, first.fields.size)
        assertEquals(39.8, first.fields.getValue("dKM") / 10.0)
        assertEquals(-6.4, first.fields.getValue("AA"))
        assertEquals(0.2, first.fields.getValue("AB"))
        assertEquals(6.2, first.fields.getValue("AC"))
        assertEquals(140.88, first.fields.getValue("V"))
        assertEquals(32.68, first.fields.getValue("Cmt"))
        assertEquals(60001.0, first.fields.getValue("IM"))
        // The first sighting of a page shape cannot be confirmed yet; the second one can.
        assertTrue(first.suspect)
        assertFalse(records[1].suspect)
    }

    @Test
    fun phaseCurrentsSumToZero() {
        // The identification of AA/AB/AC as the three phase currents rests on this holding for every
        // record of the capture, so it is worth pinning as a property rather than as three numbers.
        for (record in parseWhole(ascii(CleanCapture))) {
            val sum = record.fields.getValue("AA") +
                record.fields.getValue("AB") +
                record.fields.getValue("AC")
            assertTrue(sum in -0.15..0.15, "phase currents sum to ${'$'}sum")
        }
    }

    @Test
    fun readsTheHeaderUptimeAndWeekday() {
        val first = parseWhole(ascii(CleanCapture)).first()

        assertEquals(30, first.uptimeSeconds)
        // `/2` is the RTC weekday, Monday as 1 - not the diagnostic page.
        assertEquals(2, first.headerWeekday)
    }

    @Test
    fun readsAPageThatPrintsNoHeader() {
        val records = parseWhole(ascii(HeaderlessPage) + ascii(HeaderlessPage))

        val record = records.first()
        assertEquals(8100.0, record.fields.getValue("Ha"))
        assertEquals(2513.0, record.fields.getValue("ApS"))
        assertEquals(8703.0, record.fields.getValue("CpS"))
        // Anchoring a parser on the dated header would miss this page entirely.
        assertNull(record.uptimeSeconds)
        assertNull(record.headerWeekday)
    }

    @Test
    fun packetHeadersSplicedMidTokenAreRemoved() {
        // In this slice the framing lands inside numbers and names: `1StR @47.05Vmx`,
        // `60001>StR @Fs`, `46>VqdStR @    60>Out`. Removing the six bytes restores the record.
        val records = parseWhole(FramedCapture)

        assertEquals(1, records.size)
        val record = records.first()
        // `1StR @47.05Vmx` in the raw slice: the header split the number in half.
        assertEquals(147.05, record.fields.getValue("Vmx"))
        assertEquals(46.0, record.fields.getValue("Vqd"))
        assertEquals(60.0, record.fields.getValue("Out"))
        assertEquals(60001.0, record.fields.getValue("Fs"))
        assertEquals(47, record.uptimeSeconds)
        // Stripping the six-byte headers restores the page to its exact 384-byte length,
        // delimiter included - the figure quoted throughout the notes.
        assertEquals(384, record.byteLength)
    }

    @Test
    fun aHeaderSplitAcrossTwoNotificationsIsStillRemoved() {
        // Payloads are 20 bytes, so the six-byte header is regularly cut in half by the boundary.
        val parser = LeaperKimStrRecordParser()
        val whole = FramedCapture
        val records = mutableListOf<LeaperKimStrRecord>()
        var offset = 0
        while (offset < whole.size) {
            val end = minOf(offset + 20, whole.size)
            records += parser.ingest(whole.copyOfRange(offset, end), 1_000L + offset)
            offset = end
        }

        assertEquals(parseWhole(whole).map { it.fields }, records.map { it.fields })
    }

    @Test
    fun chunkedIngestMatchesWholeIngest() {
        val parser = LeaperKimStrRecordParser()
        val whole = ascii(CleanCapture)
        val records = mutableListOf<LeaperKimStrRecord>()
        var offset = 0
        while (offset < whole.size) {
            val end = minOf(offset + 20, whole.size)
            records += parser.ingest(whole.copyOfRange(offset, end), 1_000L + offset)
            offset = end
        }

        assertEquals(parseWhole(whole).map { it.fields }, records.map { it.fields })
    }

    @Test
    fun aRecordThatLostBytesIsMarkedSuspect() {
        // What ring overflow does: a contiguous run of bytes disappears. Take the intact capture,
        // learn the page from the first record, then cut 40 bytes out of the second.
        val text = CleanCapture
        val secondStart = text.indexOf(RecordDelimiterText, startIndex = 4)
        val damaged = text.substring(0, secondStart + 120) + text.substring(secondStart + 160)
        val records = parseWhole(ascii(damaged))

        assertEquals(2, records.size)
        assertTrue(records[1].suspect, "a record short of its page length must be suspect")
    }

    @Test
    fun twoRecordsMergedByALostDelimiterAreMarkedSuspect() {
        val text = CleanCapture
        val secondStart = text.indexOf(RecordDelimiterText, startIndex = 4)
        // Destroy the delimiter that separates them, exactly as losing four bytes would.
        val merged = text.substring(0, secondStart) + text.substring(secondStart + 4)
        val records = parseWhole(ascii(merged))

        assertTrue(records.isNotEmpty())
        assertTrue(records.last().suspect, "a repeated field name means two records ran together")
    }


    private val MotorPageWithOverflow =
        "\r\n\r\n 8010>Ha    22>Pole 100>rAB    0>di1    0>di2  300>Dif 300>HPS 864>Di  179>JA  -30>IA  1" +
        "49>FA 32768>AC 32768>BC 32768>CC 49269>ApS 49158>BpS 49006>CpS  31806471>AD  41392486>BD  462652" +
        "91>CD -1>Dir   -15>SDr   0>isH  40542250>DS    9>LDpp   18>6Dpp    7>HdHz   0>DerC   0>Her8   0>" +
        "dpOis   0>fyOis   0>diOis   0>U1   0>U2   0>U3   0>U4   0>U5\r\n\r\n 8110>Ha    22>Pole 100>rAB " +
        "   0>di1    0>di2  300>Dif 299>HPS 864>Di  179>JA   30>IA  209>FA  1668>AC 32768>BC 32768>CC 411" +
        "42>ApS 49158>BpS 49006>CpS-532655874>AD  41392486>BD  46265291>CD  1>Dir     1>SDr   0>isH 97044" +
        "3917>DS    0>LDpp   16>6Dpp    1>HdHz   0>DerC   0>Her8   0>dpOis   0>fyOis   0>diOis   0>U1   0" +
        ">U2   0>U3   0>U4   0>U5\r\n\r\n 8010>Ha    22>Pole 100>rAB    0>di1    0>di2  300>Dif 300>HPS 8" +
        "54>Di  179>JA  -30>IA  149>FA  1995>AC 32768>BC 32768>CC 41991>ApS 49158>BpS 49006>CpS  55668854" +
        ">AD  41392486>BD  46265291>CD -1>Dir    -1>SDr   0>isH 972794808>DS    0>LDpp   16>6Dpp    0>HdH" +
        "z   0>DerC   0>Her8   0>dpOis   0>fyOis   0>diOis   0>U1   0>U2   0>U3   0>U4   0>U5"

    private val MotorPageWithDigitOverflow =
        "\r\n\r\n 8010>Ha    22>Pole 100>rAB    0>di1    0>di2  300>Dif 299>HPS 864>Di  179>JA  -30>IA  1" +
        "48>FA 16959>AC 32768>BC 32768>CC 41991>ApS 49158>BpS 49006>CpS  55668854>AD  41392486>BD  462652" +
        "91>CD -1>Dir    -1>SDr   0>isH 972794808>DS    0>LDpp   16>6Dpp    0>HdHz   0>DerC   0>Her8   0>" +
        "dpOis   0>fyOis   0>diOis   0>U1   0>U2   0>U3   0>U4   0>U5\r\n\r\n 8110>Ha    22>Pole 100>rAB " +
        "   0>di1    0>di2  300>Dif 300>HPS 864>Di  179>JA   30>IA  209>FA  3693>AC 32768>BC 32768>CC 453" +
        "29>ApS 49158>BpS 49006>CpS 218776484>AD  41392486>BD  46265291>CD  1>Dir     1>SDr   0>isH101714" +
        "0807>DS    0>LDpp   16>6Dpp    0>HdHz   0>DerC   0>Her8   0>dpOis   0>fyOis   0>diOis   0>U1   0" +
        ">U2   0>U3   0>U4   0>U5\r\n\r\n 8110>Ha    22>Pole 100>rAB    0>di1    0>di2  300>Dif 300>HPS 8" +
        "64>Di  179>JA   30>IA  210>FA  8673>AC 32768>BC 32768>CC 45329>ApS 49158>BpS 49006>CpS 218776484" +
        ">AD  41392486>BD  46265291>CD  1>Dir     1>SDr   0>isH1017140807>DS    0>LDpp   16>6Dpp    0>HdH" +
        "z   0>DerC   0>Her8   0>dpOis   0>fyOis   0>diOis   0>U1   0>U2   0>U3   0>U4   0>U5"

    private val ImuPage =
        "\r\n\r\n18:484:162  0x70>MP   -36>AX    -4>AY  1002>AZ    10>GX   -13>GY     8>GZ     0>GZ   -13" +
        ">GGY  1003>ASqt   635>RSqt   -22>ex    27>ey    -1>ez   997>q0    29>q1    -1>q2    21>q3   -23>" +
        "p   339>r   248>y    0>fMS   219>Kp     1>Kg   0>ObT  0>obtT   380>iukp    21>iuki     0>Ptb    " +
        "-9>PAref      0>FyT    0>tCNT    0>TeT 1786446830>Rtc  3800>LiC  4600>OmC   0>U1   0>U2   0>U3  " +
        " 0>U4   0>U5\r\n\r\n18:982:425  0x70>MP   -38>AX     0>AY  1006>AZ    17>GX   -12>GY     3>GZ   " +
        "  1>GZ   -12>GGY  1005>ASqt   635>RSqt   -22>ex    27>ey    -1>ez   997>q0    29>q1    -1>q2    " +
        "21>q3   -20>p   339>r   250>y    0>fMS   218>Kp     2>Kg   0>ObT  0>obtT   380>iukp    21>iuki  " +
        "   0>Ptb    -8>PAref      0>FyT    0>tCNT    0>TeT 1786446831>Rtc  3800>LiC  4600>OmC   0>U1   0" +
        ">U2   0>U3   0>U4   0>U5\r\n\r\n19:481:222  0x70>MP   -39>AX     4>AY  1004>AZ    13>GX   -15>GY" +
        "    -2>GZ     2>GZ   -15>GGY  1005>ASqt   635>RSqt   -23>ex    27>ey    -1>ez   997>q0    30>q1 " +
        "    0>q2    22>q3   -15>p   344>r   255>y    0>fMS   219>Kp    -1>Kg   0>ObT  0>obtT   380>iukp " +
        "   21>iuki     0>Ptb    -6>PAref      0>FyT    0>tCNT    0>TeT 1786446831>Rtc  3800>LiC  4600>Om" +
        "C   0>U1   0>U2   0>U3   0>U4   0>U5"

    @Test
    fun aValueThatFillsItsPrintfWidthDoesNotEatTheFieldBeforeIt() {
        // `0>isH1017140807>DS`: `%10d` with a ten-digit value leaves no padding, so the separator
        // between the two fields is simply gone. No byte was lost - the record is its usual length -
        // and both fields must survive. Verbatim from dump 20260811_141039.
        val records = parseWhole(ascii(MotorPageWithDigitOverflow))

        assertEquals(2, records.size)
        val record = records.last()
        assertEquals(0.0, record.fields.getValue("isH"))
        assertEquals(1017140807.0, record.fields.getValue("DS"))
        assertEquals(37, record.fields.size)
        assertFalse(record.suspect, "an intact record must not be marked suspect")
    }

    @Test
    fun aNegativeValueThatFillsItsWidthDoesTheSame() {
        // `49006>CpS-532655874>AD`: same overflow, this time the minus sign is what consumes the
        // padding. Verbatim from dump 20260811_141039.
        val records = parseWhole(ascii(MotorPageWithOverflow))

        assertEquals(2, records.size)
        val record = records.last()
        assertEquals(49006.0, record.fields.getValue("CpS"))
        assertEquals(-532655874.0, record.fields.getValue("AD"))
        assertEquals(37, record.fields.size)
        assertFalse(record.suspect, "an intact record must not be marked suspect")
    }

    @Test
    fun aFieldNameMayStartWithADigit() {
        val record = parseWhole(ascii(MotorPageWithOverflow)).last()

        // `16>6Dpp` is a real field. Requiring a leading letter dropped it and the value before it.
        assertEquals(16.0, record.fields.getValue("6Dpp"))
    }

    @Test
    fun aNameTheWheelPrintsTwiceIsNotTakenForADamagedRecord() {
        // The IMU page prints ` 8>GZ     0>GZ` - two fields, one name. Reading a repeat as evidence
        // of two records merged together marked the entire page unusable.
        val records = parseWhole(ascii(ImuPage))

        assertEquals(2, records.size)
        // The second sighting confirms the shape, repeated name and all.
        assertFalse(records.last().suspect, "the IMU page is intact, repeated name and all")
        val first = records.first()
        assertEquals(1002.0, first.fields.getValue("AZ"))
        assertEquals(997.0, first.fields.getValue("q0"))
        // ` 8>GZ     0>GZ`: the map keeps the later of the two, which is what the wheel printed last.
        assertEquals(0.0, first.fields.getValue("GZ"))
        // 42 tokens on the page, 41 distinct names: the repeated `GZ` collapses in the map but both
        // occurrences count towards the shape, so a merged record - the whole sequence twice over -
        // still reads as a different shape rather than as this one.
        assertEquals(41, first.fields.size)
    }

    @Test
    fun garbageProducesNoRecords() {
        // The unidentified binary tail all three 2026-08-10 captures end in must not parse.
        val noise = ByteArray(2_000) { ((it * 37 + 11) and 0xFF).toByte() }

        assertTrue(parseWhole(noise).isEmpty())
    }

    @Test
    fun aStreamWithoutDelimitersProducesNothingAndCannotGrowTheBuffer() {
        val parser = LeaperKimStrRecordParser(maxBufferBytes = 512)

        repeat(100) {
            assertTrue(parser.ingest(ByteArray(100) { 'x'.code.toByte() }, 1_000L).isEmpty())
        }
    }

    @Test
    fun recordsStillParseAfterAStretchOfGarbage() {
        // This is the state all three 2026-08-10 captures end in: a long run of unidentified bytes.
        // A reconnect has to come back cleanly rather than stay poisoned by whatever preceded it.
        val parser = LeaperKimStrRecordParser()
        parser.ingest(ByteArray(4_000) { ((it * 37 + 11) and 0xFF).toByte() }, 1_000L)

        val records = parser.ingest(ascii(CleanCapture), 2_000L)

        assertEquals(2, records.size)
        assertEquals(39.8, records.first().fields.getValue("dKM") / 10.0)
    }
}
