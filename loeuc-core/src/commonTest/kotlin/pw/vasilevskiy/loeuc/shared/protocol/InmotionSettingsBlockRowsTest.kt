package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmotionSettingsBlockRowsTest {
    @Test
    fun v12BlockCarriesTheTurnSignalBitAndTheBeamSwitchSpeed() {
        val payload = ByteArray(33)
        payload[28] = 0x2C
        payload[29] = 0x01
        payload[32] = 0x0A // bit 1 turn signal, bit 3 auto light

        val rows = InmotionSettingsBlockRows.rows(
            modelId = 72,
            payload = payload,
            payloadOffset = 5,
            hasNamedFields = true,
        )

        val turn = rows.first { it.key == InmotionSettingsBlockRows.KEY_TURN_LIGHT_STATE }
        assertEquals(1, turn.rawValue)
        assertEquals("ON", turn.valueTextEn)
        assertEquals(37, turn.sourceOffset)
        val beam = rows.first { it.key == InmotionSettingsBlockRows.KEY_BEAM_SWITCH_SPEED_THRESHOLD }
        assertEquals(300, beam.rawValue)
        assertEquals("raw 300", beam.valueTextEn)
        assertEquals(33, beam.sourceOffset)
        // Named fields exist, so no byte dump: the two rows and the length.
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.kind == KIND_READONLY })
    }

    @Test
    fun turnSignalBitReadsOffWhenClear() {
        val payload = ByteArray(33)
        payload[32] = 0x08

        val turn = InmotionSettingsBlockRows.rows(71, payload, 5, hasNamedFields = true)
            .first { it.key == InmotionSettingsBlockRows.KEY_TURN_LIGHT_STATE }

        assertEquals(0, turn.rawValue)
        assertEquals("OFF", turn.valueTextEn)
    }

    @Test
    fun v12sIsNotAV12OnTheWire() {
        val rows = InmotionSettingsBlockRows.rows(111, ByteArray(40), 6, hasNamedFields = true)

        assertEquals(listOf(InmotionSettingsBlockRows.KEY_PAYLOAD_LENGTH), rows.map { it.key })
    }

    @Test
    fun undecodedBlockIsDumpedByteByByteBehindItsLength() {
        val rows = InmotionSettingsBlockRows.rows(
            modelId = null,
            payload = byteArrayOf(0x12, 0x34, 0xAB.toByte()),
            payloadOffset = 5,
            hasNamedFields = false,
        )

        assertEquals(4, rows.size)
        assertEquals(InmotionSettingsBlockRows.KEY_PAYLOAD_LENGTH, rows[0].key)
        assertEquals(3, rows[0].rawValue)
        assertEquals("3 bytes", rows[0].valueTextEn)
        assertEquals("inmotion_setting_byte_2", rows[3].key)
        assertEquals("Inmotion: raw setting byte 2", rows[3].titleEn)
        assertEquals(0xAB, rows[3].rawValue)
        assertEquals("0xAB", rows[3].valueTextEn)
        assertEquals(7, rows[3].sourceOffset)
    }

    @Test
    fun engineBuildsTheRowsFromTheBlockItReceived() {
        val engine = InmotionProtocolEngine()
        assertTrue(engine.settingsBlockRows().isEmpty())

        val payload = ByteArray(33)
        payload[28] = 10
        payload[32] = 0x02
        engine.consume(frame(flag = 0x11, command = 0x02, data = byteArrayOf(1, 2, 7, 3)))
        engine.consume(frame(flag = 0x14, command = 0x08, data = payload))

        val rows = engine.settingsBlockRows()
        assertEquals(5, engine.settingsBlockFrameOffset())
        assertEquals(1, rows.first { it.key == InmotionSettingsBlockRows.KEY_TURN_LIGHT_STATE }.rawValue)
        assertEquals(10, rows.first { it.key == InmotionSettingsBlockRows.KEY_BEAM_SWITCH_SPEED_THRESHOLD }.rawValue)
        assertTrue(rows.none { it.key.startsWith(InmotionSettingsBlockRows.KEY_RAW_BYTE_PREFIX) })
        // Nothing here is a write target, under any value.
        rows.forEach { row -> assertNull(writeCommand(engine, row.key)) }

        engine.reset()
        assertTrue(engine.settingsBlockRows().isEmpty())
    }

    @Test
    fun aTwoByteCommandFieldMovesTheBlockOneByteLater() {
        val engine = InmotionProtocolEngine()
        engine.consume(frame(flag = 0x14, command = 0x20, data = byteArrayOf(0x20, 0x7F)))

        val rows = engine.settingsBlockRows()

        assertEquals(6, engine.settingsBlockFrameOffset())
        // An unknown model has no named fields, so the bytes are listed.
        assertEquals(6, rows.first { it.key == "inmotion_setting_byte_0" }.sourceOffset)
    }

    private fun writeCommand(engine: InmotionProtocolEngine, key: String): ByteArray? =
        engine.buildSettingCommand(key, 0)

    private fun frame(flag: Int, command: Int, data: ByteArray): ByteArray {
        val commandBytes = if (command <= 0x1F) {
            byteArrayOf((command or 0x80).toByte())
        } else {
            byteArrayOf(0xA0.toByte(), (command and 0xFF).toByte())
        }
        val body = commandBytes + data
        val payload = byteArrayOf(flag.toByte(), body.size.toByte()) + body
        val checksum = payload.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }.toByte()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload + byteArrayOf(checksum)
    }
}
