package example.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The packet is a contract with your firmware: pin it byte for byte, as you would any wire format. */
class HudPacketTest {

    private val full = HudValues(
        speedKmh = 31.5,
        pwmPercent = 42.0,
        batteryPercent = 76.5,
        voltage = 141.1,
        temperatureC = -3.2,
        powerW = 1234.0,
        tripKm = 12.345,
        playback = false,
    )

    @Test
    fun encodesEveryFieldLittleEndian() {
        val bytes = HudPacket.encode(full)
        assertEquals(HudPacket.SIZE, bytes.size)
        assertArrayEquals(
            byteArrayOf(
                0x4C, 0x01, 0x00, 0x00,
                0x3B, 0x01, // 315
                0xA4.toByte(), 0x01, // 420
                0xFD.toByte(), 0x02, // 765
                0x83.toByte(), 0x05, // 1411
                0xE0.toByte(), 0xFF.toByte(), // -32
                0xD2.toByte(), 0x04, // 1234
                0x39, 0x30, 0x00, 0x00, // 12345 m
            ),
            bytes,
        )
    }

    @Test
    fun absentReadingsAreMarkedNotZeroed() {
        val bytes = HudPacket.encode(HudValues(null, null, null, null, null, null, null, playback = true))
        assertEquals(HudPacket.FLAG_PLAYBACK, bytes[2].toInt())
        assertEquals(0xFF.toByte(), bytes[4])
        assertEquals(0x7F.toByte(), bytes[5])
        assertEquals(0xFF.toByte(), bytes[8])
        assertEquals(0xFF.toByte(), bytes[9])
        (16..19).forEach { assertEquals(0xFF.toByte(), bytes[it]) }
    }

    @Test
    fun outOfRangeIsClampedBelowTheAbsentMarker() {
        val bytes = HudPacket.encode(full.copy(powerW = 99_999.0))
        assertEquals(0xFE.toByte(), bytes[14])
        assertEquals(0x7F.toByte(), bytes[15])
    }

    @Test
    fun decodesHudEvents() {
        assertEquals(HudEvent.ShortPress, HudPacket.decodeEvent(byteArrayOf(1, 1)))
        assertEquals(HudEvent.LongPress, HudPacket.decodeEvent(byteArrayOf(1, 2)))
        assertEquals(HudEvent.Battery(87), HudPacket.decodeEvent(byteArrayOf(2, 87)))
        assertNull(HudPacket.decodeEvent(byteArrayOf(9, 9)))
        assertNull(HudPacket.decodeEvent(byteArrayOf(1)))
    }
}
