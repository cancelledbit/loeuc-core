package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaperKimLogSessionTest {

    /**
     * A page-33 frame with a truthful length byte, which is what the reassembler reads. The record
     * layout itself is the one `LeaperKimLogRecordTest` builds; only the header differs.
     */
    private fun logFrame(
        index: Int,
        totalCount: Int = 1234,
        code: Int = 0x0143,
        page: Int = 33,
        text: String = "mCoilOver166",
    ): ByteArray {
        val body = ArrayList<Byte>()
        repeat(42) { body.add(0x11) }
        body.add(page.toByte())
        body.add((totalCount / 16).toByte())
        body.add((((totalCount % 16) shl 4) or (index / 256)).toByte())
        body.add((index % 256).toByte())
        body.add(1); body.add(2); body.add(3); body.add(4)
        body.add(((code shr 8) and 0xFF).toByte()); body.add((code and 0xFF).toByte())
        body.add(1)
        body.add(0); body.add(0); body.add(0); body.add(7)
        for (char in text) body.add(char.code.toByte())
        body.add(0)
        repeat(4) { body.add(0x77) }
        return byteArrayOf(0xDC.toByte(), 0x5A, 0x5C, body.size.toByte()) + body.toByteArray()
    }

    /** An ordinary page-0 telemetry frame: the same shape, nothing the log reader wants. */
    private fun telemetryFrame(): ByteArray {
        val body = ArrayList<Byte>()
        repeat(42) { body.add(0x11) }
        body.add(0)
        repeat(40) { body.add(0x22) }
        return byteArrayOf(0xDC.toByte(), 0x5A, 0x5C, body.size.toByte()) + body.toByteArray()
    }

    @Test
    fun collectsRecordsFromAStreamSplitAcrossNotifications() {
        val session = LeaperKimLogSession()
        val stream = logFrame(index = 1) + logFrame(index = 2)
        // 20 bytes at a time, the way notifications arrive at the default MTU.
        stream.toList().chunked(20).forEach { session.consume(it.toByteArray()) }

        assertEquals(listOf(1, 2), session.records.map { it.index })
        assertEquals(2, session.framesSeen)
        assertFalse(session.isEnded)
    }

    @Test
    fun theSameRecordTwiceIsOneRecord() {
        val session = LeaperKimLogSession()
        session.consume(logFrame(index = 5))
        session.consume(logFrame(index = 5))

        assertEquals(1, session.records.size)
        assertEquals(2, session.framesSeen)
    }

    @Test
    fun theEndMarkerEndsTheReadAndIsNotARecord() {
        val session = LeaperKimLogSession()
        session.consume(logFrame(index = 1))
        session.consume(logFrame(index = LEAPERKIM_LOG_END_INDEX_PAGE33))

        assertTrue(session.isEnded)
        assertEquals(listOf(1), session.records.map { it.index })
    }

    @Test
    fun pagesSummaryTellsSilenceFromTheWrongPages() {
        val session = LeaperKimLogSession()
        repeat(3) { session.consume(telemetryFrame()) }
        session.consume(logFrame(index = 1))

        assertEquals("0×3, 33×1", session.pagesSummary())
        assertEquals(4, session.framesSeen)
    }

    @Test
    fun resetStartsTheReadOver() {
        val session = LeaperKimLogSession()
        session.consume(logFrame(index = 1))
        session.consume(logFrame(index = LEAPERKIM_LOG_END_INDEX_PAGE33))
        session.reset()

        assertEquals(emptyList(), session.records)
        assertEquals(0, session.framesSeen)
        assertFalse(session.isEnded)
        assertEquals("", session.pagesSummary())
    }

    @Test
    fun theFirstRecordIsWaitedForLongerThanTheNextOne() {
        assertEquals(LeaperKimLogSession.NoAnswerTimeoutMillis, LeaperKimLogSession.timeoutMillis(0))
        assertEquals(LeaperKimLogSession.EndOfLogTimeoutMillis, LeaperKimLogSession.timeoutMillis(1))
        assertTrue(LeaperKimLogSession.NoAnswerTimeoutMillis > LeaperKimLogSession.EndOfLogTimeoutMillis)
    }

    @Test
    fun theReassemblerKeepsAMarkerSplitAcrossNotifications() {
        val reassembler = LeaperKimFrameReassembler()
        val frame = logFrame(index = 9)
        assertEquals(emptyList(), reassembler.consume(byteArrayOf(0x00, 0xDC.toByte(), 0x5A)))
        val frames = reassembler.consume(byteArrayOf(0x5C) + frame.copyOfRange(3, frame.size))

        assertEquals(1, frames.size)
        assertEquals(frame.toList(), frames[0].toList())
    }
}
