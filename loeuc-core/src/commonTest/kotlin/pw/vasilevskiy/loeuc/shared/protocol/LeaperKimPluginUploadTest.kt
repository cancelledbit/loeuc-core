package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The frames that carry a plugin into the wheel's second flash bank.
 *
 * These were read back off the emulator before they were written down here: the wheel executed
 * exactly this sequence, and `0x08080000` afterwards held the plugin's magic. So the shapes below
 * are a record of something that worked, not a guess at what should.
 */
@OptIn(ExperimentalWriteApi::class)
class LeaperKimPluginUploadTest {

    private fun frames(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            out.add(bytes.copyOfRange(at, at + 16))
            at += 16
        }
        return out
    }

    @Test
    fun first_frame_unlocks_and_the_second_erases_the_first_page() {
        val all = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(byteArrayOf(1, 2)))
        assertEquals(0x61, all[0][6].toInt() and 0xFF, "family byte")
        assertEquals(0, all[0][9].toInt(), "sub 0 is unlock")
        assertEquals(1, all[1][9].toInt(), "sub 1 is erase")
        assertEquals(2, all[2][9].toInt(), "sub 2 is write")
    }

    @Test
    fun the_magic_is_written_last_so_a_dropped_link_leaves_no_plugin() {
        // The firmware recognises a plugin by the four bytes at the start of the bank and jumps
        // into whatever follows. Send those first and every wheel that loses the link mid-upload
        // is left calling into erased flash twice a second, across power cycles. So they go last:
        // until then the bank holds a body no one will look at.
        val payload = byteArrayOf(0x4C, 0x50, 0x4C, 0x47, 1, 2, 3, 4)
        val all = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(payload))
        val writes = all.filter { it[9].toInt() == 2 }
        assertEquals(4, writes.size)
        assertEquals(2, writes[0][8].toInt(), "the body starts at halfword 2")
        assertEquals(3, writes[1][8].toInt())
        assertEquals(0, writes[2][8].toInt(), "and the magic comes last")
        assertEquals(1, writes[3][8].toInt())
    }

    @Test
    fun every_page_is_erased_before_any_byte_is_written() {
        val payload = ByteArray(16) { it.toByte() }
        val all = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(payload, pageSize = 8))
        val lastErase = all.indexOfLast { it[9].toInt() == 1 }
        val firstWrite = all.indexOfFirst { it[9].toInt() == 2 }
        assertTrue(lastErase < firstWrite, "an erase after a write would take the page back out")
    }

    @Test
    fun a_halfword_goes_out_little_endian_at_its_own_offset() {
        val all = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xFE.toByte(), 0xCA.toByte())))
        val writes = all.filter { it[9].toInt() == 2 }
        val first = writes.single { it[8].toInt() == 0 }
        val second = writes.single { it[8].toInt() == 1 }
        assertEquals(0xEF, first[10].toInt() and 0xFF)
        assertEquals(0xBE, first[11].toInt() and 0xFF)
        assertEquals(0xFE, second[10].toInt() and 0xFF)
        assertEquals(0xCA, second[11].toInt() and 0xFF)
    }

    @Test
    fun a_page_boundary_gets_its_own_erase() {
        // Two pages of payload with a tiny page size: an erase before each page and nowhere else.
        val payload = ByteArray(16) { it.toByte() }
        val all = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(payload, pageSize = 8))
        val erases = all.count { it[9].toInt() == 1 }
        assertEquals(2, erases, "one erase per page, and not one more")
    }

    @Test
    fun an_odd_payload_is_padded_with_erased_flash() {
        val all = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(byteArrayOf(0x11)))
        val write = all.last()
        assertEquals(0x11, write[10].toInt() and 0xFF)
        assertEquals(0xFF, write[11].toInt() and 0xFF, "the tail reads as erased flash, not as zero")
    }

    @Test
    fun a_plugin_command_names_its_addressee() {
        // The wheel hands the frame to every installed plugin, so the frame has to say which one
        // it is for. Without that, a command meant for one is answered by all of them.
        val frame = LeaperKimProtocolEngine().buildPluginCommandFrame(pluginId = 2, opcode = 1, value = 300)
        assertEquals(16, frame.size)
        assertEquals(0x61, frame[6].toInt() and 0xFF, "the plugin family")
        assertEquals(2, frame[7].toInt(), "the addressee")
        assertEquals(1, frame[8].toInt(), "the opcode")
        assertEquals(3, frame[9].toInt(), "hand the frame to the plugins")
        assertEquals(300, (frame[10].toInt() and 0xFF) or ((frame[11].toInt() and 0xFF) shl 8))
    }

    @Test
    fun a_slot_moves_every_offset_in_the_upload() {
        // Slot 1 starts one flash page into the bank — 4 KB above the base, which is 2048
        // halfwords — and every frame of the upload has to say so: the receiver has no cursor and
        // takes the address from the frame. The page above 512 KB is four kilobytes on this part,
        // and a slot smaller than a page means installing one plugin erases its neighbour.
        val frames = frames(LeaperKimProtocolEngine().buildPluginUploadFrames(byteArrayOf(1, 2, 3, 4), slot = 1))
        val offsets = frames.map { ((it[7].toInt() and 0xFF) shl 8) or (it[8].toInt() and 0xFF) }
        assertEquals(2048, offsets.min(), "nothing lands below the slot")
        assertEquals(2049, offsets.max(), "and nothing above the two halfwords written")
    }

    @Test
    fun a_slot_outside_the_bank_is_refused() {
        // Thirty-two slots of four kilobytes each - the entire plugin budget.
        val engine = LeaperKimProtocolEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.buildPluginUploadFrames(byteArrayOf(1, 2), slot = 32)
        }
    }

    @Test
    fun a_frame_is_twelve_bytes_and_a_four_byte_trailer() {
        val bytes = LeaperKimProtocolEngine().buildPluginUploadFrames(byteArrayOf(1, 2, 3, 4))
        assertEquals(0, bytes.size % 16, "every frame is twelve bytes of body and four of CRC32")
        assertEquals(4, bytes.size / 16, "unlock, erase, and one write per halfword")
    }
}
