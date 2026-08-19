package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `packetUtil::check_pass` sends command 0x02 with the UTF-8 password and a trailing NUL, then
 * reads a five-byte answer whose own command byte is 0x04 - not the 0x02 it asked with - and
 * takes the first payload byte as the verdict: 0x02 accepted, anything else rejected. A missing
 * or corrupt answer is a third outcome the vendor reports separately, and here is simply the
 * absence of a parsed verdict.
 */
class HwChargerPasswordTest {

    @Test
    fun `the password command carries utf8 and a terminating nul`() {
        val command = HwChargerProtocolEngine().checkPasswordCommand("1234")

        // length, command, four password bytes, NUL, checksum
        assertEquals(8, command.size)
        assertEquals(7, command[0].toInt())
        assertEquals(HW_CHARGER_CMD_CHECK_PASS, command[1].toInt() and 0xFF)
        assertEquals("1234", command.copyOfRange(2, 6).decodeToString())
        assertEquals(0, command[6].toInt())
    }

    @Test
    fun `an empty password is still a well formed command`() {
        val command = HwChargerProtocolEngine().checkPasswordCommand("")

        assertEquals(4, command.size)
        assertEquals(3, command[0].toInt())
        assertEquals(HW_CHARGER_CMD_CHECK_PASS, command[1].toInt() and 0xFF)
        assertEquals(0, command[2].toInt())
    }

    @Test
    fun `a non-ascii password is encoded as utf8 - not truncated`() {
        val command = HwChargerProtocolEngine().checkPasswordCommand("пароль")

        assertEquals("пароль", command.copyOfRange(2, command.size - 2).decodeToString())
    }

    @Test
    fun `verdict 0x02 is accepted and anything else is rejected`() {
        assertEquals(
            HwChargerPasswordVerdict.Accepted,
            parseHwChargerPasswordAnswer(passwordAnswer(HW_CHARGER_PASSWORD_ACCEPTED)),
        )
        assertEquals(
            HwChargerPasswordVerdict.Rejected,
            parseHwChargerPasswordAnswer(passwordAnswer(0x01)),
        )
        assertEquals(
            HwChargerPasswordVerdict.Rejected,
            parseHwChargerPasswordAnswer(passwordAnswer(0x00)),
        )
    }

    @Test
    fun `a telemetry frame is not a password answer`() {
        val telemetry = buildHwChargerCommand(
            HW_CHARGER_CMD_GET_VALUES,
            ByteArray(HW_CHARGER_VALUES_PAYLOAD_SIZE),
        )

        assertNull(parseHwChargerPasswordAnswer(telemetry))
    }

    @Test
    fun `a five byte frame with the wrong command byte is not a password answer`() {
        val answer = buildHwChargerCommand(0x05, byteArrayOf(HW_CHARGER_PASSWORD_ACCEPTED.toByte(), 0x00))

        assertNull(parseHwChargerPasswordAnswer(answer))
    }

    @Test
    fun `the answer is reassembled out of the stream`() {
        val answer = passwordAnswer(HW_CHARGER_PASSWORD_ACCEPTED)

        val result = reassembleHwChargerFrames(byteArrayOf(0x11, 0x22) + answer)
        assertEquals(1, result.frames.size)
        assertEquals(HW_CHARGER_PASSWORD_RESPONSE_COMMAND, result.frames[0].command)
        assertEquals(0, result.remainingBuffer.size)
    }

    @Test
    fun `an answer followed by telemetry both survive one pass`() {
        val stream = passwordAnswer(HW_CHARGER_PASSWORD_ACCEPTED) +
            buildHwChargerCommand(
                HW_CHARGER_CMD_GET_VALUES,
                ByteArray(HW_CHARGER_VALUES_PAYLOAD_SIZE),
            )

        val result = reassembleHwChargerFrames(stream)
        assertEquals(2, result.frames.size)
        assertEquals(HW_CHARGER_PASSWORD_RESPONSE_COMMAND, result.frames[0].command)
        assertEquals(HW_CHARGER_VALUES_PAYLOAD_SIZE, result.frames[1].payload.size)
    }

    /**
     * Three of the answer's five bytes are pinned - length, command and checksum - so unrelated
     * wheel traffic must not turn into a verdict. A charger that is merely noisy would otherwise
     * be able to unlock itself.
     */
    @Test
    fun `junk does not produce a password answer`() {
        val junk = ByteArray(4096) { index -> (index * 7 + index / 3).toByte() }

        val verdicts = reassembleHwChargerFrames(junk).frames
            .mapNotNull { parseHwChargerPasswordAnswer(it.bytes) }
        assertEquals(0, verdicts.size)
    }

    @Test
    fun `a streaming engine reports the verdict once`() {
        val engine = HwChargerProtocolEngine()
        engine.consume(passwordAnswer(HW_CHARGER_PASSWORD_ACCEPTED))

        assertEquals(HwChargerPasswordVerdict.Accepted, engine.takePasswordVerdict())
        assertNull(engine.takePasswordVerdict())
    }

    @Test
    fun `a split answer is verdicted only once both halves arrive`() {
        val answer = passwordAnswer(HW_CHARGER_PASSWORD_ACCEPTED)
        val engine = HwChargerProtocolEngine()

        engine.consume(answer.copyOfRange(0, 3))
        assertNull(engine.takePasswordVerdict())

        engine.consume(answer.copyOfRange(3, answer.size))
        assertNotNull(engine.takePasswordVerdict())
    }

    @Test
    fun `reset clears a pending verdict`() {
        val engine = HwChargerProtocolEngine()
        engine.consume(passwordAnswer(HW_CHARGER_PASSWORD_ACCEPTED))

        engine.reset()
        assertNull(engine.takePasswordVerdict())
    }

    private fun passwordAnswer(verdict: Int): ByteArray {
        return buildHwChargerCommand(
            HW_CHARGER_PASSWORD_RESPONSE_COMMAND,
            byteArrayOf(verdict.toByte(), 0x00),
        )
    }
}
