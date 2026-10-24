package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

@OptIn(ExperimentalWriteApi::class)
class ModuleCommandFramesTest {
    private val engine = LeaperKimProtocolEngine()
    private fun assertCrc(frame: ByteArray) {
        val expected = LeaperKimFirmwareDigests.crc32(frame, 0, frame.size - 4)
        var actual = 0L
        for (b in frame.takeLast(4)) actual = (actual shl 8) or (b.toLong() and 255)
        assertEquals(expected, actual)
    }
    @Test fun moduleCallsCarryNewIdAndExplicitVersionBeforeCrc() {
        val frame = engine.buildModuleCommandFrame(4, 0x12, 0xFEDC, 1)
        assertEquals(17, frame.size)
        assertContentEquals(byteArrayOf(0x11,1,0x61,0x44,0x12,3,0xDC.toByte(),0xFE.toByte(),1), frame.copyOfRange(4,13))
        assertCrc(frame)
        assertFailsWith<IllegalArgumentException> { engine.buildModuleCommandFrame(0, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { engine.buildModuleCommandFrame(4, 0, 0, 0) }
    }
    @Test fun autoLightModuleKeepsItsId() {
        val frame = engine.buildModuleCommandFrame(12, 0xFE, 1, 1)
        assertEquals(17, frame.size)
        assertContentEquals(byteArrayOf(0x11, 1, 0x61, 0x4C, 0xFE.toByte(), 3, 1, 0, 1), frame.copyOfRange(4, 13))
        assertCrc(frame)
    }
    @Test fun fieldWeakeningModuleUsesTheLastSupportedId() {
        val frame = engine.buildModuleCommandFrame(13, 3, 750, 1)
        assertEquals(17, frame.size)
        assertContentEquals(byteArrayOf(0x11, 1, 0x61, 0x4D, 3, 3, 0xEE.toByte(), 2, 1), frame.copyOfRange(4, 13))
        assertCrc(frame)
        assertFailsWith<IllegalArgumentException> { engine.buildModuleCommandFrame(14, 0, 0, 1) }
    }
    @Test fun maintenanceAddressesAreBoundedAndResetHasSeparateKey() {
        for (page in 0..31) {
            val erase = engine.buildModuleMaintenanceFrame(1,page,0)
            val check = engine.buildModuleMaintenanceFrame(4,page,15)
            assertEquals(16, erase.size); assertEquals(16, check.size)
            val halfwords = ((erase[7].toInt() and 255) shl 8) or (erase[8].toInt() and 255)
            assertEquals(page * 2048, halfwords)
            assertEquals(page, check[8].toInt()); assertEquals(15, check[10].toInt())
            assertCrc(erase); assertCrc(check)
        }
        val reset = engine.buildModuleMaintenanceFrame(5,0,0)
        assertContentEquals(byteArrayOf(0,0,5,0x7A,0x5E),reset.copyOfRange(7,12))
        assertCrc(reset)
        assertFailsWith<IllegalArgumentException> { engine.buildModuleMaintenanceFrame(1,32,0) }
        assertFailsWith<IllegalArgumentException> { engine.buildModuleMaintenanceFrame(4,0,16) }
        assertFailsWith<IllegalArgumentException> { engine.buildModuleMaintenanceFrame(2,0,0) }
    }
}
