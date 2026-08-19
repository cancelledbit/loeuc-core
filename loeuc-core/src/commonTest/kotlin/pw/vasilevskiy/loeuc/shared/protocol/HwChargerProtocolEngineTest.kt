package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Framing comes from the vendor app's own `packetUtil::send` and `packetUtil::recv_packet`
 * (`hwcdq_1.3.0.apk`, read through blutter — see `docs/hw-charger-protocol-notes.md`):
 *
 *     [0]        payload.length + 2, i.e. how many bytes follow
 *     [1]        command
 *     [2..]      payload
 *     [last]     (command + sum(payload)) & 0xFF     <- the length byte is NOT summed
 *
 * The receiver reads one byte, then that many more, and checks the same sum. It never looks at
 * the response's command byte.
 */
class HwChargerProtocolEngineTest {

    @Test
    fun `the telemetry poll is the three bytes the vendor app sends`() {
        val cmd = buildHwChargerCommand(HW_CHARGER_CMD_GET_VALUES)

        // 0x02 bytes follow: the command, and its checksum.
        assertEquals(3, cmd.size)
        assertEquals(0x02.toByte(), cmd[0])
        assertEquals(0x06.toByte(), cmd[1])
        assertEquals(0x06.toByte(), cmd[2])
    }

    @Test
    fun `the length byte counts the bytes after it - and the checksum skips it`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val frame = buildHwChargerCommand(HW_CHARGER_CMD_CHECK_PASS, payload)

        assertEquals(6, frame.size)
        assertEquals(5, frame[0].toInt())
        assertEquals(HW_CHARGER_CMD_CHECK_PASS, frame[1].toInt() and 0xFF)
        // 0x02 + 0x10 + 0x20 + 0x30, and no contribution from the leading 0x05.
        assertEquals(0x62, frame[5].toInt() and 0xFF)
    }

    @Test
    fun testConsumeFrameValidTelemetry() {
        val frame = telemetryFrame {
            writeFloatLe(0, 220.0f) // inputVoltage
            writeFloatLe(4, 2.0f) // inputCurrent
            writeFloatLe(8, 50.0f) // inputFrequency
            writeFloatLe(12, 38.5f) // temperature
            writeFloatLe(16, 36.0f) // temperature1
            writeFloatLe(20, 84.0f) // outputVoltage
            writeFloatLe(24, 5.0f) // outputCurrent
            writeFloatLe(28, 4.9f) // currentPoint
            writeFloatLe(32, 92.5f) // efficiency
            this[36] = 1 // outputState
            writeFloatLe(37, 12.5f) // ah
            writeFloatLe(41, 1050.0f) // wh
            this[45] = 1 // moduleNumber
        }
        assertEquals(HW_CHARGER_TELEMETRY_FRAME_SIZE, frame.size)

        val engine = HwChargerProtocolEngine()
        val telemetry = engine.consumeFrame(frame)
        assertNotNull(telemetry)
        assertEquals(220.0, telemetry.inputVoltage, 0.01)
        assertEquals(2.0, telemetry.inputCurrent, 0.01)
        assertEquals(50.0, telemetry.inputFrequency, 0.01)
        assertEquals(38.5, telemetry.temperature, 0.01)
        assertEquals(36.0, telemetry.temperature1, 0.01)
        assertEquals(84.0, telemetry.outputVoltage, 0.01)
        assertEquals(5.0, telemetry.outputCurrent, 0.01)
        assertEquals(4.9, telemetry.currentPoint, 0.01)
        assertEquals(92.5, telemetry.efficiency, 0.01)
        assertEquals(1, telemetry.outputState)
        assertEquals(12.5, telemetry.ah, 0.01)
        assertEquals(1050.0, telemetry.wh, 0.01)
        assertEquals(1, telemetry.moduleNumber)
        assertEquals(440.0, telemetry.inputPower, 0.01)
        assertEquals(420.0, telemetry.outputPower, 0.01)
    }

    /**
     * A frame straight off `HWCDQ_40FE0409` (`loeuc_dump_1786784010.jsonl`, 2026-08-16): mains
     * present, output closed, so every battery-side reading is zero and only the module number
     * is set. This is the capture that showed the field order was wrong - the old decoder read
     * the 49.94 Hz mains frequency as the pack voltage.
     */
    @Test
    fun `a real charger frame decodes as mains present with the output closed`() {
        val frame = ("300600605f430000000000c2474200808a410000c04111041143" +
            "0000000000000000000000000000000000000000000109").hexToByteArrayForTest()

        val telemetry = HwChargerProtocolEngine().consumeFrame(frame)
        assertNotNull(telemetry)
        assertEquals(223.375, telemetry.inputVoltage, 0.01)
        assertEquals(0.0, telemetry.inputCurrent, 0.01)
        assertEquals(49.94, telemetry.inputFrequency, 0.01)
        assertEquals(17.31, telemetry.temperature, 0.01)
        assertEquals(24.0, telemetry.temperature1, 0.01)
        assertEquals(145.02, telemetry.outputVoltage, 0.01)
        assertEquals(0.0, telemetry.outputCurrent, 0.01)
        assertEquals(0, telemetry.outputState)
        assertEquals(1, telemetry.moduleNumber)
    }

    /**
     * The response's command byte is whatever the charger echoes: the vendor app drops it
     * unread, so nothing here may depend on a particular value.
     */
    @Test
    fun `any response command byte decodes`() {
        val engine = HwChargerProtocolEngine()

        for (command in listOf(0x06, 0x0C, 0x86)) {
            val frame = telemetryFrame(command = command) { writeFloatLe(20, 84.2f) }
            val telemetry = engine.consumeFrame(frame)
            assertNotNull(telemetry, "command 0x${command.toString(16)} should decode")
            assertEquals(84.2, telemetry.outputVoltage, 0.01)
        }
    }

    @Test
    fun testChunkedConsumption() {
        val frame = telemetryFrame { writeFloatLe(20, 100.0f) }

        val engine = HwChargerProtocolEngine()
        assertNull(engine.consume(frame.copyOfRange(0, 20)))

        val result = engine.consume(frame.copyOfRange(20, frame.size))
        assertNotNull(result)
        assertEquals(100.0, result.outputVoltage, 0.01)
    }

    @Test
    fun testInvalidChecksum() {
        val frame = telemetryFrame { }
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()

        val engine = HwChargerProtocolEngine()
        assertNull(engine.consumeFrame(frame))
    }

    @Test
    fun `a length byte that disagrees with the frame is rejected`() {
        val frame = telemetryFrame { }
        frame[0] = 0x20

        val engine = HwChargerProtocolEngine()
        assertNull(engine.consumeFrame(frame))
    }

    @Test
    fun testJunkStreamProducesNoFrames() {
        // Sync is one length byte plus one checksum byte, which is thin. Unrelated wheel traffic
        // must not manufacture telemetry out of it - an earlier version of this decoder scanned
        // for any plausible length and produced phantom frames on half the dumps in the repo.
        val junk = ByteArray(512) { index -> (0x07 + (index % 8)).toByte() }
        assertEquals(0, reassembleHwChargerFrames(junk).frames.size)

        val engine = HwChargerProtocolEngine()
        assertNull(engine.consume(junk))
    }

    @Test
    fun `a run of length bytes alone is not a frame`() {
        val junk = ByteArray(512) { HW_CHARGER_VALUES_LENGTH_BYTE.toByte() }
        assertEquals(0, reassembleHwChargerFrames(junk).frames.size)
    }

    @Test
    fun testFrameIsFoundAfterLeadingJunk() {
        val frame = telemetryFrame { writeFloatLe(20, 84.2f) }
        val stream = byteArrayOf(0x07, 0x55, 0x0C, 0x01) + frame

        val result = reassembleHwChargerFrames(stream)
        assertEquals(1, result.frames.size)
        assertEquals(0, result.remainingBuffer.size)

        val engine = HwChargerProtocolEngine()
        val telemetry = engine.consume(stream)
        assertNotNull(telemetry)
        assertEquals(84.2, telemetry.outputVoltage, 0.01)
    }

    @Test
    fun testBackToBackFramesBothDecode() {
        val first = telemetryFrame { writeFloatLe(20, 70.0f) }
        val second = telemetryFrame { writeFloatLe(20, 71.0f) }

        val result = reassembleHwChargerFrames(first + second)
        assertEquals(2, result.frames.size)
        assertEquals(0, result.remainingBuffer.size)
    }

    @Test
    fun testPartialFrameStaysBuffered() {
        val frame = telemetryFrame { writeFloatLe(20, 60.0f) }

        val result = reassembleHwChargerFrames(frame.copyOfRange(0, 30))
        assertEquals(0, result.frames.size)
        assertEquals(30, result.remainingBuffer.size)
    }

    private fun telemetryFrame(
        command: Int = HW_CHARGER_CMD_GET_VALUES,
        fill: ByteArray.() -> Unit,
    ): ByteArray {
        return buildHwChargerCommand(command, ByteArray(HW_CHARGER_VALUES_PAYLOAD_SIZE).apply(fill))
    }

    private fun String.hexToByteArrayForTest(): ByteArray {
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.writeFloatLe(offset: Int, value: Float) {
        val bits = value.toBits()
        this[offset] = (bits and 0xFF).toByte()
        this[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        this[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        this[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }
}
