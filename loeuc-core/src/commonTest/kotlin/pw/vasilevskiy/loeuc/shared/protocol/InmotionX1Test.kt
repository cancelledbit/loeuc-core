package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The InMotion X1 against frames it sent during a ride recorded by another app, as they came
 * off the wire. That app asked addressed questions and the X1 answered addressed: main info in
 * formats 2 and 3, everything else from the main board (`0x21`) to the app (`0x02`) in format 6.
 * The expected values were checked against that recording: power is voltage times current, the
 * pack voltage is the sum of the BMS cell voltages, and the speed follows the phone's GPS.
 */
class InmotionX1Test {

    @Test
    fun theAddressedMainInfoNamesTheModel() {
        val engine = InmotionProtocolEngine()
        assertNull(engine.modelName())

        engine.consume(X1_MODEL.bytes())

        assertEquals("X1", engine.modelName())
    }

    @Test
    fun anAddressedRealtimeAnswerDecodesWithTheP6Offsets() {
        val engine = InmotionProtocolEngine()
        engine.consume(X1_MODEL.bytes())

        val update = assertNotNull(engine.consume(X1_REALTIME.bytes()))
        val telemetry = assertNotNull(update.telemetry)

        assertEquals(0x04, update.responseCommand)
        assertEquals("inmotion_v14", telemetry.profileId)
        assertEquals(226.32, telemetry.voltage, 0.001)
        assertEquals(2.22, telemetry.batteryCurrent, 0.001)
        assertEquals(30.0, telemetry.speedKmh, 0.001)
        assertEquals(22.48, telemetry.pwmPercent, 0.001)
        assertEquals(14.15, telemetry.torque, 0.001)
        assertEquals(502.0, telemetry.power, 0.001)
        assertEquals(473.0, telemetry.motorPower, 0.001)
        assertEquals(0.27, telemetry.pitchAngle, 0.001)
        assertEquals(-0.69, telemetry.lateralAngle, 0.001)
        assertEquals(6_960.0, telemetry.tripDistanceMeters, 0.001)
        assertEquals(86.0, telemetry.batteryPercent, 0.001)
        assertEquals(85.56, telemetry.rideBatteryPercent, 0.001)
        assertEquals(120.0, telemetry.speedTiltBackKmh, 0.001)
        assertEquals(500.0, telemetry.phaseCurrentLimitAmps, 0.001)
        assertEquals(38.0, telemetry.mosTemperature, 0.001)
        assertEquals(64.0, telemetry.motorTemperature, 0.001)
        assertEquals(53.0, telemetry.boardTemperature, 0.001)
        assertEquals(1.0, telemetry.motorEnabled, 0.001)
        assertTrue(telemetry.tirePressureBar.isNaN())
    }

    /**
     * The same payload in the shape LoEUC's own `0x14` request is answered in by every other
     * model. Which of the two an X1 sends LoEUC is not recorded yet, so both have to work.
     */
    @Test
    fun theUnaddressedShapeOfTheSameAnswerDecodesTheSame() {
        val addressed = InmotionProtocolEngine().apply { consume(X1_MODEL.bytes()) }
            .consume(X1_REALTIME.bytes())?.telemetry
        val plain = InmotionProtocolEngine().apply { consume(X1_MODEL.bytes()) }
            .consume(unaddressed(X1_REALTIME.bytes()))?.telemetry

        assertEquals(assertNotNull(addressed), assertNotNull(plain))
    }

    @Test
    fun theIntegratedRealtimeReadIsAskedAndItsMaximaKept() {
        val engine = InmotionProtocolEngine()
        assertNull(engine.integratedRealtimeCommand())
        engine.consume(X1_MODEL.bytes())
        assertNotNull(engine.integratedRealtimeCommand())

        engine.consume(X1_REALTIME.bytes())
        val telemetry = assertNotNull(engine.consume(X1_INTEGRATED.bytes())?.telemetry)

        assertEquals(30.0, telemetry.speedKmh, 0.001)
        assertEquals(113.78, telemetry.wheelMaxSpeedKmh, 0.001)
        assertEquals(18_453.0, telemetry.wheelMaxPowerW, 0.001)
        assertEquals(11_172.0, telemetry.wheelMaxMotorPowerW, 0.001)
        // Zero in every X1 answer: no tire pressure sensor, not a flat tire.
        assertTrue(telemetry.tirePressureBar.isNaN())
    }

    @Test
    fun anAddressedTotalStatsAnswerIsTheOdometer() {
        val engine = InmotionProtocolEngine()
        engine.consume(X1_MODEL.bytes())
        assertEquals(0x11, engine.consume(X1_TOTAL_STATS.bytes())?.responseCommand)

        val telemetry = assertNotNull(engine.consume(X1_REALTIME.bytes())?.telemetry)

        assertEquals(420_110.0, telemetry.totalDistanceMeters, 0.001)
    }

    @Test
    fun anAddressedErrorAnswerIsTheDiagnosticsFrame() {
        val engine = InmotionProtocolEngine()
        engine.consume(X1_MODEL.bytes())

        val diagnostics = assertNotNull(engine.consume(X1_ERRORS.bytes())?.diagnosticsSnapshot)

        assertEquals(0L, diagnostics.errorCode)
        assertTrue(diagnostics.decodedItems.none { it.rawValue != 0 })
    }

    @Test
    fun theBmsIsAskedAndAnswersAsAP6Does() {
        val engine = InmotionProtocolEngine()
        assertTrue(engine.bmsReadCommands().isEmpty())
        engine.consume(X1_MODEL.bytes())
        assertTrue(engine.bmsReadCommands().isNotEmpty())

        engine.consume(X1_BMS_GENERAL.bytes())
        val bms = assertNotNull(engine.consume(X1_BMS_CELLS.bytes())?.bmsSnapshot)

        val pack = bms.batteries.single()
        assertEquals(56, pack.cells.size)
        assertEquals(4.109, pack.cells.min(), 0.0001)
        assertEquals(4.117, pack.cells.max(), 0.0001)
        assertEquals(4.118, assertNotNull(pack.direct?.maxCellVoltage), 0.0001)
        assertEquals(4.109, assertNotNull(pack.direct?.minCellVoltage), 0.0001)
    }

    /**
     * The X1 also sent frames between other addresses at connect (`AA AA 16 04 02 23 8B …`).
     * Read at byte 4 this one is command `0x02`, main info - which would tell an app that main
     * info has been answered.
     */
    @Test
    fun aFrameBetweenOtherAddressesIsNotAMainBoardAnswer() {
        val engine = InmotionProtocolEngine()

        assertNull(engine.consume(X1_OTHER_ADDRESSES.bytes()))
        assertNull(engine.modelName())
    }

    @Test
    fun mainBoardCommandIndexFollowsTheFormat() {
        assertEquals(4, InmotionV2Frames.mainBoardCommandIndex("AAAA14010410".bytes()))
        assertEquals(5, InmotionV2Frames.mainBoardCommandIndex(X1_MODEL.bytes()))
        assertEquals(6, InmotionV2Frames.mainBoardCommandIndex(X1_REALTIME.bytes()))
        assertNull(InmotionV2Frames.mainBoardCommandIndex(X1_BMS_CELLS.bytes()))
        assertNull(InmotionV2Frames.mainBoardCommandIndex(X1_OTHER_ADDRESSES.bytes()))
    }

    /** [frame] with its source and target dropped and the flag turned to `0x14`. */
    private fun unaddressed(frame: ByteArray): ByteArray {
        val body = byteArrayOf(0x14, (frame[3] - 2).toByte()) + frame.copyOfRange(6, frame.size - 1)
        val checksum = body.fold(0) { value, byte -> value xor (byte.toInt() and 0xFF) }
        val escaped = body.flatMap { byte ->
            if (byte == 0xAA.toByte() || byte == 0xA5.toByte()) listOf(0xA5.toByte(), byte) else listOf(byte)
        }
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + escaped.toByteArray() + checksum.toByte()
    }

    private fun String.bytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val X1_MODEL = "AAAA120902820102110101010088"
        const val X1_REALTIME =
            "AAAA16592102846858DE0000000000B80B00008705C808F601D9011B00BBFF00000000B8020000B704" +
                "98216C21983AE02EDF2E10273421E02EE02E50C300000000D6F000E5B0E6CAD4B025000200000000" +
                "49000000000000000000000003"
        const val X1_INTEGRATED =
            "AAAA166521028701006858DE0000000000B80B00008705C808F601D90198216C21983AE02EDF2ED6F0" +
                "E502722C1548A42BB802F81701003C29000037050000660D00001BA400002B623700AC4D0900092E" +
                "01009C7503000000490000000000000000000000B7042B1BF5"
        const val X1_TOTAL_STATS = "AAAA161B2102911BA4000035623700D24D0900092E01009D750300D90198215A"
        const val X1_ERRORS = "AAAA160B2102830000000000000000BD"
        const val X1_BMS_GENERAL =
            "AAAA163D2402810100862E977C1D5A0000000088135613000016100D105014D7D5D5D5CACECECECFCE" +
                "CECECDB0B0B00000FFFF0000002A0000000000000000000058"
        const val X1_BMS_CELLS =
            "AAAA16732402821310151014101410131015101410131014100D10141014101210141013101210111013" +
                "101210101011100F101510141014101310141013100D10131012101310121012101110131012101210" +
                "121012101510131014101510131014101310131012101510121013101210131010101210DF"
        const val X1_OTHER_ADDRESSES = "AAAA160402238BA5A51D"
    }
}
