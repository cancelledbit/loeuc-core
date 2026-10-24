package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The P6 integrated realtime answer (`0x87`): the realtime values again, laid over the last
 * `0x04` reading, plus the wheel's trip maxima and LTE signal.
 *
 * Frames are from a P6 ridden at speed (`loeuc_E17CC2C97F8B_unknown_20260922_015443.jsonl`),
 * as they came off the wire, 270 ms apart.
 */
class InmotionIntegratedRealtimeTest {

    @Test
    fun theReadIsP6Only() {
        val engine = InmotionProtocolEngine()
        assertNull(engine.integratedRealtimeCommand())

        engine.consume(P6_MODEL.bytes())
        assertEquals("AAAA14010712", assertNotNull(engine.integratedRealtimeCommand()).hex())

        val v11 = InmotionProtocolEngine()
        v11.consume(V11_MODEL.bytes())
        assertNull(v11.integratedRealtimeCommand())
    }

    @Test
    fun theAnswerRefreshesTheRealtimeFieldsAndKeepsTheRest() {
        val engine = InmotionProtocolEngine()
        engine.consume(P6_MODEL.bytes())
        val realtime = assertNotNull(engine.consume(P6_REALTIME.bytes())?.telemetry)
        assertEquals(78.44, realtime.speedKmh, 0.001)
        assertEquals(54.37, realtime.pwmPercent, 0.001)
        assertTrue(realtime.wheelMaxSpeedKmh.isNaN())

        val integrated = assertNotNull(engine.consume(P6_INTEGRATED.bytes())?.telemetry)
        assertEquals("inmotion_p6", integrated.profileId)
        assertEquals(75.61, integrated.speedKmh, 0.001)
        assertEquals(51.83, integrated.pwmPercent, 0.001)
        assertEquals(206.85, integrated.voltage, 0.001)
        assertEquals(60.08, integrated.batteryCurrent, 0.001)
        assertEquals(107.86, integrated.torque, 0.001)
        assertEquals(12427.0, integrated.power, 0.001)
        assertEquals(9089.0, integrated.motorPower, 0.001)
        assertEquals(79.0, integrated.batteryPercent, 0.001)
        assertEquals(78.35, integrated.rideBatteryPercent, 0.001)
        assertEquals(82.0, integrated.motorTemperature, 0.001)
        assertEquals(40.0, integrated.boardTemperature, 0.001)
        assertEquals(17_330.0, integrated.tripDistanceMeters, 0.001)
        assertEquals(2.13, integrated.tirePressureBar, 0.001)
        // Not in the `0x87` answer: stays what the `0x04` frame said.
        assertEquals(realtime.pitchAngle, integrated.pitchAngle, 0.001)
        assertEquals(realtime.imuTemperature, integrated.imuTemperature, 0.001)
        assertEquals(realtime.motorEnabled, integrated.motorEnabled, 0.001)

        assertEquals(114.21, integrated.wheelMaxSpeedKmh, 0.001)
        assertEquals(18160.0, integrated.wheelMaxPowerW, 0.001)
        assertEquals(14030.0, integrated.wheelMaxMotorPowerW, 0.001)
        assertEquals(4.0, integrated.lteSignal, 0.001)

        // The next `0x04` reading brings its own values and keeps the extras.
        val next = assertNotNull(engine.consume(P6_REALTIME.bytes())?.telemetry)
        assertEquals(78.44, next.speedKmh, 0.001)
        assertEquals(114.21, next.wheelMaxSpeedKmh, 0.001)
        assertEquals(4.0, next.lteSignal, 0.001)
    }

    @Test
    fun anAnswerBeforeAnyRealtimeFramePublishesNothingButIsKept() {
        val engine = InmotionProtocolEngine()
        engine.consume(P6_MODEL.bytes())
        assertNull(engine.consume(P6_INTEGRATED.bytes())?.telemetry)

        val realtime = assertNotNull(engine.consume(P6_REALTIME.bytes())?.telemetry)
        assertEquals(78.44, realtime.speedKmh, 0.001)
        assertEquals(114.21, realtime.wheelMaxSpeedKmh, 0.001)
    }

    @Test
    fun resetForgetsTheExtras() {
        val engine = InmotionProtocolEngine()
        engine.consume(P6_MODEL.bytes())
        engine.consume(P6_INTEGRATED.bytes())
        engine.reset()
        engine.consume(P6_MODEL.bytes())
        val realtime = assertNotNull(engine.consume(P6_REALTIME.bytes())?.telemetry)
        assertTrue(realtime.wheelMaxSpeedKmh.isNaN())
        assertTrue(realtime.lteSignal.isNaN())
    }

    private fun String.bytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()

    private companion object {
        const val P6_MODEL = "AAAA11088201020D01010100" + "94"

        // Raywing's V11, `loeuc_C6286FC174E2_unknown_20260918_203339.jsonl`.
        const val V11_MODEL = "AAAA11088201020601020100" + "9C"
        const val P6_REALTIME =
            "AAAA1457840E50D41900000000A41E0000B52B3D15EE3436265302630084016400C606D5004C25DC1E9B1E" +
                "983A204E983A1027401FE02EE02E50C300000000C80200D8B0D6CCD2B06100040000000049000200000000" +
                "00000000001B"
        const val P6_INTEGRATED =
            "AAAA1463870100CD50781700000000891D0000222A3F148B308123DC1E9B1E983A204E983AC802D8049D2C" +
                "F046CE36C5066E450400E05E000071070000D3100000EB9305008D515604B0E64F00E1840400D2C01400D5" +
                "00490002000000000000000000F423A21CF0"
    }
}
