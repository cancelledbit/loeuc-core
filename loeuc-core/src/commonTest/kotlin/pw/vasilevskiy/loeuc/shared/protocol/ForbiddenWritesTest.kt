package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi::class)
class ForbiddenWritesTest {

    private fun kingSong(command: Int, sub: Int = 0) = ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55
        this[2] = sub.toByte()
        this[16] = command.toByte()
        this[17] = 0x14
        this[18] = 0x5A
        this[19] = 0x5A
    }

    @Test
    fun begodeCalibrationAndBootloaderAreRefused() {
        listOf('c', '!', '@').forEach { assertTrue(ForbiddenWrites.begode(byteArrayOf(it.code.toByte())), "$it") }
        assertTrue(ForbiddenWrites.begode("cy".encodeToByteArray()))
    }

    @Test
    fun ordinaryBegodeCommandsPass() {
        listOf("b", "Q", "y", "W").forEach { assertFalse(ForbiddenWrites.begode(it.encodeToByteArray()), it) }
        assertFalse(ForbiddenWrites.begode(ByteArray(0)))
    }

    @Test
    fun kingSongCalibrationAndGyroAreRefused() {
        assertTrue(ForbiddenWrites.kingSong(kingSong(0x89)))
        (0..4).forEach { sub -> assertTrue(ForbiddenWrites.kingSong(kingSong(0x8A, sub)), "sub $sub") }
    }

    @Test
    fun kingSongSettingsAndHornPass() {
        assertFalse(ForbiddenWrites.kingSong(KingSongSettingsWriter.chargeLimit(80)))
        assertFalse(ForbiddenWrites.kingSong(KingSongSettingsWriter.horn()))
        assertFalse(ForbiddenWrites.kingSong(byteArrayOf(0x89.toByte())))
    }
}
