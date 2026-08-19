package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class SolowheelXtremeProtocolEngineTest {
    @Test
    fun reassemblesRealCaptureAcrossBleChunks() {
        val engine = SolowheelXtremeProtocolEngine()

        assertNull(engine.consume(" 00025, 00553, 00001".encodeToByteArray()))
        val telemetry = engine.consume(",\r\n".encodeToByteArray())

        assertEquals(25, telemetry?.motionRaw)
        assertEquals(2.0 / 3.0, assertNotNull(telemetry).speedKmh, 0.000001)
        assertEquals(55.3, telemetry.voltage)
        assertEquals(1, telemetry.stateRaw)
    }

    @Test
    fun keepsLatestCompleteLineAndRejectsNoise() {
        val result = reassembleSolowheelXtremeFrames(
            "noise\r\n 00000, 00554, 00001,\r\n 00021, 00553, 00000,\r\npartial".encodeToByteArray(),
        )

        assertEquals(2, result.frames.size)
        assertEquals("partial", result.remainingBuffer.decodeToString())
        assertEquals(21, decodeSolowheelXtremeFrame(result.frames.last())?.motionRaw)
    }

    @Test
    fun consumesLatestOfSeveralLinesAndResetDropsPartialState() {
        val engine = SolowheelXtremeProtocolEngine()
        val latest = engine.consume(
            " 00000, 00554, 00001,\r\n 00013, 00553, 00000,\r\n".encodeToByteArray(),
        )
        assertEquals(13, latest?.motionRaw)
        assertEquals(0, latest?.stateRaw)

        assertNull(engine.consume(" 00025, 00553".encodeToByteArray()))
        engine.reset()
        assertNull(engine.consume(", 00001,\r\n".encodeToByteArray()))
    }

    @Test
    fun rejectsMalformedRecordsWithoutLosingFollowingValidRecord() {
        val result = reassembleSolowheelXtremeFrames(
            "short\r\n 00000; 00554, 00001,\r\n 00000, 00554, 00001,\r\n".encodeToByteArray(),
        )
        assertEquals(1, result.frames.size)
        assertTrue(result.remainingBuffer.isEmpty())
        assertNull(decodeSolowheelXtremeFrame(byteArrayOf()))
        assertNull(decodeSolowheelXtremeFrame(" 00000, 00554, 00001,\rX".encodeToByteArray()))
    }

    @Test
    fun stripsBinaryNoiseImmediatelyBeforeFixedWidthRecord() {
        val result = reassembleSolowheelXtremeFrames(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + " 00160, 00553, 00000,\r\n".encodeToByteArray(),
        )
        assertEquals(1, result.frames.size)
        assertEquals(64.0 / 15.0, assertNotNull(decodeSolowheelXtremeFrame(result.frames.single())).speedKmh, 0.000001)
    }

    @Test
    fun convertsMaximumFreeSpinCapture() {
        val telemetry = assertNotNull(
            decodeSolowheelXtremeFrame(" 01091, 00551, 00000,\r\n".encodeToByteArray()),
        )
        assertEquals(29.093333333333334, telemetry.speedKmh, 0.000001)
    }

    @Test
    fun convertsExternallyObservedPedalRaiseSpeed() {
        val telemetry = assertNotNull(
            decodeSolowheelXtremeFrame(" 00675, 00541, 00000,\r\n".encodeToByteArray()),
        )
        assertEquals(18.0, telemetry.speedKmh)
    }
}
