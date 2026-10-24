package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real smart-BMS frames from three different Begodes: a 107 V wheel that fills all eight cell
 * pages, a 210 V one, and a third that answers the pack summary with zeroes throughout. The
 * third is the important one - it is why a zero here cannot be published as a reading.
 */
class BegodeBmsTest {
    @Test
    fun splitsTheCellPagesIntoTheTwoPacksTheyCameFrom() {
        val engine = BegodeProtocolEngine()
        CELL_PAGES.forEach { frame -> engine.consume(frame.bytes()) }

        val bms = assertNotNull(engine.bmsSnapshot())
        assertEquals("begode_smart_bms", bms.profileId)
        assertEquals(listOf(1, 2), bms.batteries.map { it.index })
        assertEquals(listOf(32, 32), bms.batteries.map { it.cells.size })
        assertEquals(listOf(32, 32), bms.batteries.map { it.expectedCellCount })
        assertEquals(listOf(1, 32), listOf(bms.batteries[0].cells.first().index, bms.batteries[0].cells.last().index))
        // First cell of page 0 of each pack, verbatim from the capture.
        assertEquals(3.378, bms.batteries[0].cells.first().voltage)
        assertEquals(3.378, bms.batteries[1].cells.first().voltage)
        // A cell of the second pack must not land in the first: the two arrive on the same
        // offsets and only the frame type tells them apart.
        assertEquals(3.382, bms.batteries[1].cells[1].voltage)
        assertEquals(3.373, bms.batteries[0].cells[1].voltage)
        // 64 cells at about 3.38 V is the 107.7 V this wheel's pack summary reports.
        val sum = bms.batteries.flatMap { it.cells }.sumOf { it.voltage }
        assertEquals(107.7, sum / 2.0, 1.0)
    }

    @Test
    fun readsTheBatteryTemperaturesOfTwoWheelsThatMeasureThem() {
        val small = BegodeProtocolEngine()
        val smallTelemetry = assertNotNull(small.consume(PACK_SUMMARY_107V.bytes()))
        assertEquals(25.0, smallTelemetry.batteryTemperatureMax)
        assertEquals(24.0, smallTelemetry.batteryTemperatureMin)

        val large = BegodeProtocolEngine()
        val largeTelemetry = assertNotNull(large.consume(PACK_SUMMARY_210V.bytes()))
        assertEquals(34.0, largeTelemetry.batteryTemperatureMax)
        assertEquals(31.0, largeTelemetry.batteryTemperatureMin)
        // Same frame, same offsets: a decode that had picked up the pack voltage instead would
        // read 210 here, and the two wheels would not disagree by three degrees.
        assertEquals(209.8, largeTelemetry.voltage, 0.01)
    }

    @Test
    fun aWheelWithoutBatterySensorsReportsNothingRatherThanZeroDegrees() {
        // An overheat alert acts on the number it is given; a hard zero standing in for "this
        // wheel has no battery sensor" would read as a battery sitting at freezing point.
        val engine = BegodeProtocolEngine()
        val telemetry = assertNotNull(engine.consume(PACK_SUMMARY_NO_SENSORS.bytes()))

        assertTrue(telemetry.batteryTemperatureMax.isNaN())
        assertTrue(telemetry.batteryTemperatureMin.isNaN())
    }

    @Test
    fun aWheelWithoutASmartBmsHasNoBatteryTabsAtAll() {
        val engine = BegodeProtocolEngine()
        engine.consume(LIVE_TELEMETRY.bytes())

        assertNull(engine.bmsSnapshot())
    }
}

private fun String.bytes(): ByteArray =
    filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Eight pages, four per pack, of a 64-cell wheel. */
private val CELL_PAGES = listOf(
    "55 AA 0D 32 0D 2D 0D 2B 0D 2E 0D 31 0D 2E 0D 2D 0D 2E 02 00 5A 5A 5A 5A",
    "55 AA 0D 2B 0D 2D 0D 2F 0D 2B 0D 32 0D 37 0D 36 0D 39 02 01 5A 5A 5A 5A",
    "55 AA 0D 31 0D 35 0D 2E 0D 2A 0D 32 0D 31 0D 32 0D 2D 02 02 5A 5A 5A 5A",
    "55 AA 0D 2D 0D 2E 0D 31 0D 32 0D 2D 0D 2E 0D 2B 0D 2F 02 03 5A 5A 5A 5A",
    "55 AA 0D 32 0D 36 0D 36 0D 32 0D 31 0D 2F 0D 2B 0D 2D 03 00 5A 5A 5A 5A",
    "55 AA 0D 31 0D 34 0D 2F 0D 2B 0D 2E 0D 31 0D 31 0D 2F 03 01 5A 5A 5A 5A",
    "55 AA 0D 2B 0D 32 0D 28 0D 2B 0D 2F 0D 2B 0D 2C 0D 31 03 02 5A 5A 5A 5A",
    "55 AA 0D 27 0D 31 0D 2E 0D 35 0D 31 0D 2E 0D 31 0D 35 03 03 5A 5A 5A 5A",
)

private const val PACK_SUMMARY_107V = "55 AA 00 5A 00 00 04 35 00 00 00 19 00 18 02 1C 7D 01 01 00 5A 5A 5A 5A"

private const val PACK_SUMMARY_210V = "55 AA 00 55 00 0E 08 32 00 04 00 22 00 1F 04 18 7D 01 01 00 5A 5A 5A 5A"

private const val PACK_SUMMARY_NO_SENSORS =
    "55 AA 00 55 FF D7 06 07 00 00 00 00 00 00 00 00 00 00 01 00 5A 5A 5A 5A"

private const val LIVE_TELEMETRY = "55 AA 15 09 00 00 00 16 00 02 01 2C F9 E3 10 89 00 08 00 18 5A 5A 5A 5A"
