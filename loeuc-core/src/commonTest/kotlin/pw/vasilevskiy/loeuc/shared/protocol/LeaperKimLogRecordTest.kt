package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaperKimLogRecordTest {

    /**
     * Builds a page-33 frame the way the wheel packs one: header, 42 bytes of ordinary main-frame
     * body, the page id at 46, then the record.
     */
    private fun logFrame(
        totalCount: Int = 1234,
        index: Int = 7,
        code: Int = 0x0143,
        values: List<Long> = listOf(1L, -1L, 17296L, 5553L),
        text: String = "mCoilOver166",
        trailingBytes: Int = 4,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xDC.toByte()); out.add(0x5A); out.add(0x5C); out.add(0)
        repeat(42) { out.add(0x11) }
        out.add(33)
        // totalCount occupies 12 bits across 47..48, index the next 12 across 48..49.
        out.add((totalCount / 16).toByte())
        out.add((((totalCount % 16) shl 4) or (index / 256)).toByte())
        out.add((index % 256).toByte())
        out.add(1); out.add(2); out.add(3); out.add(4)
        out.add(((code shr 8) and 0xFF).toByte()); out.add((code and 0xFF).toByte())
        out.add(values.size.toByte())
        for (value in values) {
            val raw = if (value < 0) value + 4294967296L else value
            out.add(((raw shr 24) and 0xFF).toByte())
            out.add(((raw shr 16) and 0xFF).toByte())
            out.add(((raw shr 8) and 0xFF).toByte())
            out.add((raw and 0xFF).toByte())
        }
        for (char in text) out.add(char.code.toByte())
        out.add(0)
        repeat(trailingBytes) { out.add(0x77) }
        return out.toByteArray()
    }

    @Test
    fun `parses a full log record`() {
        val record = parseLeaperKimLogRecord(logFrame())
        checkNotNull(record)
        assertEquals(1234, record.totalCount)
        assertEquals(7, record.index)
        assertEquals(0x0143, record.code)
        assertEquals(listOf(1, 2, 3, 4), record.timeBytes)
        assertEquals(listOf(1L, -1L, 17296L, 5553L), record.values)
        assertEquals("mCoilOver166", record.text)
    }

    /** The three temperatures of a real `mCoilOver166` record decode as hundredths of a degree. */
    @Test
    fun `decodes the documented mCoilOver166 sample`() {
        val record = parseLeaperKimLogRecord(
            logFrame(values = listOf(0L, 17296L, 5553L, 6622L), text = "mCoilOver166"),
        )
        checkNotNull(record)
        assertEquals(listOf(0L, 17296L, 5553L, 6622L), record.values)
        assertEquals(172.96, record.values[1] / 100.0, 0.001)
    }

    @Test
    fun `rejects frames that are not the log page`() {
        val frame = logFrame()
        frame[46] = 0
        assertNull(parseLeaperKimLogRecord(frame))
    }

    @Test
    fun `rejects frames shorter than the wheel's own guard`() {
        // 56 payload bytes plus the four CRC bytes is exactly the size the firmware refuses.
        assertNull(parseLeaperKimLogRecord(ByteArray(60).also { it[46] = 33 }))
    }

    @Test
    fun `escapes non-ascii text instead of mangling it`() {
        val frame = logFrame(values = emptyList(), text = "ab")
        // Overwrite the second text byte with a GBK lead byte.
        frame[57 + 1] = 0xB0.toByte()
        val record = parseLeaperKimLogRecord(frame)
        checkNotNull(record)
        assertEquals("a\\xB0", record.text)
    }

    /** Byte-identical to `BtManager.CMD_READ_LOG` and `CMD_READ_LOG_NEW`, each with its own CRC32. */
    @Test
    fun `read log command matches the official app`() {
        val command = buildLeaperKimReadLogCommand()
        assertEquals(40, command.size)
        val legacy = command.copyOfRange(0, 16).map { it.toInt() and 0xFF }
        assertEquals(
            listOf(0x4C, 0x6B, 0x41, 0x70, 0x14, 0x01) + List(9) { 0x80 } + listOf(0x01),
            legacy,
        )
        val modern = command.copyOfRange(20, 36).map { it.toInt() and 0xFF }
        assertEquals(
            listOf(0x4C, 0x64, 0x41, 0x70, 0x14, 0x01, 0x00) + List(8) { 0x80 } + listOf(0x01),
            modern,
        )
    }

    @Test
    fun `stop command differs from start only in the value byte`() {
        val start = buildLeaperKimReadLogCommand()
        val stop = buildLeaperKimStopLogCommand()
        assertEquals(start.size, stop.size)
        assertEquals(0x01, start[15].toInt() and 0xFF)
        assertEquals(0xFF, stop[15].toInt() and 0xFF)
        assertEquals(0x01, start[35].toInt() and 0xFF)
        assertEquals(0xFF, stop[35].toInt() and 0xFF)
        // Different payloads must produce different CRCs, or the wheel would accept the wrong one.
        assertTrue(!start.copyOfRange(16, 20).contentEquals(stop.copyOfRange(16, 20)))
    }
}
