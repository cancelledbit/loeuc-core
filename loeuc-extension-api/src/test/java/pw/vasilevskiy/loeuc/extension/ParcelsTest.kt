package pw.vasilevskiy.loeuc.extension

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParcelsTest {

    private fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>, trailer: Int? = null): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            trailer?.let(parcel::writeInt)
            parcel.setDataPosition(0)
            val read = creator.createFromParcel(parcel)
            trailer?.let { assertEquals("the reader must stop at its own end", it, parcel.readInt()) }
            return read
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun telemetryRoundTripsWithAndWithoutPosition() {
        val snapshot = TelemetrySnapshot(
            timestampMillis = 1_700_000_000_123L,
            mode = TelemetryMode.PLAYBACK,
            codes = intArrayOf(ExtensionMetric.SPEED_KMH, ExtensionMetric.PWM_PERCENT),
            values = doubleArrayOf(31.5, 42.0),
            latitude = 55.75,
            longitude = 37.62,
        )
        val read = roundTrip(snapshot, TelemetrySnapshot.CREATOR, trailer = 77)
        assertEquals(snapshot.timestampMillis, read.timestampMillis)
        assertTrue(read.isPlayback)
        assertEquals(31.5, read.value(ExtensionMetric.SPEED_KMH)!!, 0.0)
        assertNull(read.value(ExtensionMetric.POWER_W))
        assertEquals(55.75, read.latitude!!, 0.0)

        val hidden = roundTrip(
            TelemetrySnapshot(1L, TelemetryMode.LIVE, intArrayOf(), doubleArrayOf(), null, null),
            TelemetrySnapshot.CREATOR,
        )
        assertNull(hidden.latitude)
        assertNull(hidden.longitude)
    }

    @Test
    fun commandParcelsRoundTrip() {
        val withValue = roundTrip(CommandValue(7, 12.5), CommandValue.CREATOR, trailer = 1)
        assertEquals(7, withValue.requestId)
        assertEquals(12.5, withValue.value!!, 0.0)
        assertNull(roundTrip(CommandValue(8, null), CommandValue.CREATOR).value)

        val result = roundTrip(
            CommandResult(7, "level", RefusalReason.MOVING, byteArrayOf(1, 2)),
            CommandResult.CREATOR,
            trailer = 2,
        )
        assertEquals("level", result.key)
        assertEquals(RefusalReason.MOVING, result.outcome)
        assertArrayEquals(byteArrayOf(1, 2), result.bytes)
    }

    @Test
    fun smallParcelsRoundTrip() {
        val device = roundTrip(DeviceDescriptor("Wheel", null, "1.2"), DeviceDescriptor.CREATOR, trailer = 3)
        assertEquals("Wheel", device.name)
        assertNull(device.model)
        assertEquals(9.5, roundTrip(TileValue(9.5), TileValue.CREATOR).value, 0.0)
        val alert = roundTrip(AlertRequest(true, 0.25), AlertRequest.CREATOR)
        assertTrue(alert.active)
        assertEquals(0.25, alert.intensity, 0.0)
        val capabilities = roundTrip(HostCapabilities(1, 5, true, listOf("beep")), HostCapabilities.CREATOR, trailer = 4)
        assertEquals(listOf("beep"), capabilities.availableCommands)
    }

    @Test
    fun aNewerWriterFieldIsSkippedByAnOlderReader() {
        // A newer host appended a field to TileValue. This reader must take the value it knows
        // and land exactly after the whole parcel, where the next argument begins.
        val parcel = Parcel.obtain()
        try {
            parcel.writeSized {
                writeDouble(3.0)
                writeLong(123L)
            }
            parcel.writeInt(99)
            parcel.setDataPosition(0)
            assertEquals(3.0, TileValue.CREATOR.createFromParcel(parcel).value, 0.0)
            assertEquals(99, parcel.readInt())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun anOlderWriterLeavesNewFieldsAtTheirDefaults() {
        // An older host wrote AlertRequest before intensity existed.
        val parcel = Parcel.obtain()
        try {
            parcel.writeSized { writeInt(1) }
            parcel.setDataPosition(0)
            val read = AlertRequest.CREATOR.createFromParcel(parcel)
            assertTrue(read.active)
            assertEquals(1.0, read.intensity, 0.0)
        } finally {
            parcel.recycle()
        }
    }
}
