package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every frame here is verbatim from one P6 capture, both BMS units of the same wheel in the
 * same minute. That is what makes the two reads checkable against each other: the spread the
 * pack summary reports has to be the spread of the cell array the other read returned, and a
 * decode that is off by a byte cannot satisfy both.
 */
class InmotionDirectBmsTest {
    @Test
    fun readsThePackSummaryOfARealBms() {
        val response = assertNotNull(InmotionDirectBms.response(BMS_ONE_SUMMARY.bytes()))
        assertEquals(0x24, response.sourceAddress)
        assertEquals(0x02, response.targetAddress)
        assertEquals(InmotionDirectBms.SELECTOR_REALTIME, response.selector)

        val realtime = assertNotNull(InmotionDirectBms.realtime(response.payload))
        assertEquals(10.0, realtime.fullCapacityAh)
        assertEquals(9.7, realtime.remainCapacityAh)
        assertEquals(97.0, realtime.capacityLevelPercent)
        assertEquals(4.124, realtime.maxCellVoltage)
        assertEquals(4.113, realtime.minCellVoltage)
        // The four board sensors, then the per-cell array, which ends at the first raw zero.
        assertEquals(37.0, realtime.chargeMosTemperature)
        assertEquals(39.0, realtime.dischargeMosTemperature)
        assertEquals(36.0, realtime.boardTemperature1)
        assertEquals(36.0, realtime.boardTemperature2)
        assertEquals(12, realtime.temperatures.size)
        assertEquals(listOf(1, 12), listOf(realtime.temperatures.first().index, realtime.temperatures.last().index))
        assertEquals(26.0, realtime.temperatures.minOf { it.celsius })
        assertEquals(28.0, realtime.temperatures.maxOf { it.celsius })
        // This firmware leaves its own current pair and its cycle counter at a hard zero.
        assertNull(realtime.chargeCurrentAmps)
        assertNull(realtime.dischargeCurrentAmps)
        assertNull(realtime.fullChargeCycles)
    }

    @Test
    fun theCellArrayAgreesWithTheSpreadTheSummaryReported() {
        val summary = assertNotNull(
            InmotionDirectBms.realtime(
                assertNotNull(InmotionDirectBms.response(BMS_ONE_SUMMARY.bytes())).payload,
            ),
        )
        val cells = InmotionDirectBms.cells(
            assertNotNull(InmotionDirectBms.response(BMS_ONE_CELLS.bytes())).payload,
        )

        assertEquals(56, cells.size)
        assertEquals(summary.maxCellVoltage, cells.max())
        assertEquals(summary.minCellVoltage, cells.min())
        // Twice the reading of one cell would still pass a per-cell range check; the pack
        // voltage the summary carries is what makes a doubled or halved scale visible.
        assertEquals(230.6, cells.sum(), 0.5)
    }

    @Test
    fun readsASecondWheelsPackSummaryWithTheSameOffsets() {
        // A different P6, a different day, one BMS unit: the offsets are the layout, not this
        // one wheel's numbers. This unit sits 8.6 Ah into a 10 Ah pack and a few degrees cooler.
        val payload = (
            "01000622D9A8345900000000102798210000EE0FE90F3011D1D5D2D2CCCACACB" +
                "CBCCCACBCBCCCBCB0000FFFF0000002A00000000000000000000"
            ).bytes()

        val realtime = assertNotNull(InmotionDirectBms.realtime(payload))
        assertEquals(10.0, realtime.fullCapacityAh)
        assertEquals(8.6, realtime.remainCapacityAh)
        assertEquals(86.0, assertNotNull(realtime.capacityLevelPercent), 0.001)
        assertEquals(33.0, realtime.chargeMosTemperature)
        assertEquals(37.0, realtime.dischargeMosTemperature)
        assertEquals(34.0, realtime.boardTemperature1)
        assertEquals(34.0, realtime.boardTemperature2)
        assertEquals(12, realtime.temperatures.size)
        assertEquals(28.0, realtime.temperatures.first().celsius)
        assertEquals(27.0, realtime.temperatures.last().celsius)
        assertNull(realtime.chargeCurrentAmps)
        assertNull(realtime.dischargeCurrentAmps)
        assertNull(realtime.fullChargeCycles)
        // The tail behind the temperature array is a fixed `00 00 FF FF 00 00 00 2A` on every
        // unit captured, so the 42 in it is a constant and not a humidity reading.
        assertNull(realtime.relativeHumidityPercent)
    }

    @Test
    fun aPayloadWithoutAPlausiblePackVoltageIsNotAPackSummary() {
        assertNull(InmotionDirectBms.realtime(ByteArray(58)))
    }

    @Test
    fun aFrameThisWheelDidNotSendIsNotAnAnswer() {
        // The same envelope carries the request, which travels from the host to the BMS.
        val request = "AAAA1603022401" .bytes()
        assertNull(InmotionDirectBms.response(request + request.xorChecksum()))
        // Selector 4 answers, carries something else entirely, and nothing here decodes it.
        // Its payload passes the pack-voltage anchor by coincidence and parses into nonsense
        // if handed to the summary decoder, which is why the selector - not plausibility - is
        // what decides who reads a payload.
        val undecoded = assertNotNull(InmotionDirectBms.response(BMS_ONE_UNDECODED.bytes()))
        assertEquals(InmotionDirectBms.SELECTOR_UNDECODED, undecoded.selector)
        assertTrue(InmotionDirectBms.cells(undecoded.payload).isEmpty())

        val engine = InmotionProtocolEngine()
        engine.consume(P6_MODEL.bytes())
        engine.consume(BMS_ONE_UNDECODED.bytes())
        assertNull(engine.bmsSnapshot())
    }

    @Test
    fun theEngineMergesBothBmsUnitsIntoTheBatteryTheWheelReported() {
        val engine = InmotionProtocolEngine()
        engine.consume(P6_MODEL.bytes())
        engine.consume(P6_BATTERY.bytes())
        engine.consume(BMS_ONE_SUMMARY.bytes())
        engine.consume(BMS_ONE_CELLS.bytes())
        engine.consume(BMS_TWO_SUMMARY.bytes())
        val update = assertNotNull(engine.consume(BMS_TWO_CELLS.bytes()))

        val bms = assertNotNull(update.bmsSnapshot)
        assertEquals("inmotion_p6", bms.profileId)
        assertEquals(2, bms.batteries.size)
        assertEquals(listOf(1, 2), bms.batteries.map { it.index })
        assertEquals(listOf(56, 56), bms.batteries.map { it.cells.size })
        assertEquals(listOf(12, 12), bms.batteries.map { it.temperatures.size })
        // The pack state stays the wheel's own: the `0x05` frame is the only thing that says
        // whether a slot is detected, and the direct reads say nothing about it.
        assertTrue(bms.batteries.all { it.detected && it.reported })
        assertEquals(229.99, bms.batteries[0].voltage, 0.01)
        assertEquals(4.124, assertNotNull(bms.batteries[0].direct).maxCellVoltage)
        assertEquals(4.123, assertNotNull(bms.batteries[1].direct).maxCellVoltage)
    }

    @Test
    fun aPackThatAnswersBeforeTheWheelDoesStillProducesABattery() {
        // A P6 answers the direct reads far more reliably than the `0x05` battery frame, and
        // an application that waits for the frame shows an empty BMS screen for a live wheel.
        val engine = InmotionProtocolEngine()
        engine.consume(P6_MODEL.bytes())
        val update = assertNotNull(engine.consume(BMS_ONE_CELLS.bytes()))

        val battery = assertNotNull(update.bmsSnapshot).batteries.single()
        assertEquals(56, battery.cells.size)
        assertTrue(battery.voltage.isNaN())
        assertTrue(!battery.reported)
    }
}

private fun ByteArray.xorChecksum(): ByteArray =
    byteArrayOf((2 until size).fold(0) { value, index -> value xor (this[index].toInt() and 0xFF) }.toByte())

private fun String.bytes(): ByteArray =
    filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private const val P6_MODEL = "AA AA 11 08 82 01 02 0D 01 01 01 00 94"

private const val P6_BATTERY =
    "AA AA 14 11 85 D7 59 00 00 E1 FF 01 00 D6 59 00 00 E1 FF 01 00 81"

private const val BMS_ONE_SUMMARY =
    "AA AA 16 3D 24 02 81 01 00 06 26 D9 91 14 5A 00 00 00 00 10 27 E4 25 00 00 1C 10 11 10 " +
        "24 13 D5 D7 D4 D4 CC CA CA CA CA CC CA CA CB CB CA CB 00 00 FF FF 00 00 00 2A 00 00 " +
        "00 00 00 00 00 00 00 00 4E"

private const val BMS_TWO_SUMMARY =
    "AA AA 16 3D 25 02 81 01 00 06 26 D9 95 17 5A 00 00 00 00 10 27 E4 25 00 00 1B 10 11 10 " +
        "24 13 D5 D7 D4 D4 CC CA CA CA CA CC CA CA CA CB CA CB 00 00 FF FF 00 00 00 2A 00 00 " +
        "00 00 00 00 00 00 00 00 4E"

private const val BMS_ONE_CELLS =
    "AA AA 16 73 24 02 82 18 10 18 10 1A 10 1A 10 1A 10 1C 10 1C 10 1B 10 1B 10 1B 10 19 10 " +
        "14 10 15 10 14 10 16 10 17 10 17 10 19 10 18 10 17 10 19 10 17 10 17 10 18 10 12 10 " +
        "13 10 11 10 11 10 13 10 14 10 14 10 13 10 13 10 14 10 14 10 13 10 14 10 14 10 14 10 " +
        "13 10 14 10 13 10 13 10 12 10 12 10 12 10 11 10 13 10 13 10 13 10 12 10 12 10 12 10 " +
        "12 10 11 10 12 10 CC"

private const val BMS_TWO_CELLS =
    "AA AA 16 73 25 02 82 1B 10 19 10 19 10 1B 10 1B 10 1B 10 1B 10 1B 10 19 10 18 10 19 10 " +
        "12 10 14 10 15 10 15 10 16 10 16 10 18 10 18 10 19 10 18 10 18 10 18 10 19 10 12 10 " +
        "12 10 12 10 14 10 14 10 14 10 13 10 12 10 13 10 13 10 13 10 13 10 13 10 14 10 14 10 " +
        "15 10 14 10 13 10 12 10 13 10 13 10 13 10 14 10 12 10 11 10 12 10 12 10 11 10 11 10 " +
        "11 10 12 10 12 10 C7"

private const val BMS_ONE_UNDECODED =
    "AA AA 16 3F 24 02 84 01 00 10 27 00 00 05 2B 4C 00 02 02 01 00 03 01 00 00 00 00 74 68 " +
        "69 73 20 69 73 20 64 65 66 61 75 6C 74 20 6D 65 73 73 5A 31 30 30 45 35 41 30 30 30 " +
        "32 33 00 00 00 00 00 00 00 00 E8"
