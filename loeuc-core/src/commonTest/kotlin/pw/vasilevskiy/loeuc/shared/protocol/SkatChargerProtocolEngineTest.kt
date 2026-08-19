package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every frame here is copied verbatim from a real capture of a "CAN-Control" / SKAT charger,
 * `loeuc_840D8E16B9EA_unknown_20260814_152810.jsonl` (notify FFF1, 938 frames, zero writes). The
 * expected values were confirmed against a screenshot of the vendor app taken the same day.
 */
class SkatChargerProtocolEngineTest {

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // FA telemetry frame: FA + 6 float32 le. V=165.10, I=10.00, P=1651.02, target=223, Ah=7.7457, Wh=1727.29.
    private val telemetryFrame =
        bytes("FA FE 19 25 43 00 00 20 41 7E 60 CE 44 00 00 5F 43 E7 DC F7 40 6D E9 D7 44")

    // FB status frame from the same moment: same six floats at offset 31, temperature 50.49 at offset 43.
    private val statusFrame =
        bytes(
            "FB 01 D4 05 00 00 00 00 00 00 00 00 1E 00 00 00 00 00 00 00 0B 00 00 00 00 00 00 00 " +
                "02 00 00 73 9F 26 43 00 00 20 41 50 47 D0 44 BF FA 49 42 00 00 5F 43 DB 0F FA 40 D0 D3 D9 44 " +
                "CD CC AC 41 E8 A1 CD 3E FE F2 83 42 DD 47 2F 42 14 8A E5 45 00 00 11 43 00 8A CA 46 " +
                "00 00 00 00 DC 9C 95 42 00 00 00 00",
        )

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    @Test
    fun `the FA frame decodes voltage - current - power - target - Ah and Wh`() {
        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(telemetryFrame))

        assertClose(165.10, telemetry.outputVoltage)
        assertClose(10.00, telemetry.outputCurrent)
        assertClose(1651.02, telemetry.power, tolerance = 0.1)
        assertClose(223.0, telemetry.targetVoltage)
        assertClose(7.7457, telemetry.ampHours, tolerance = 0.001)
        assertClose(1727.29, telemetry.wattHours, tolerance = 0.1)
    }

    @Test
    fun `power always equals voltage times current in a real frame`() {
        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(telemetryFrame))
        assertClose(telemetry.outputVoltage * telemetry.outputCurrent, telemetry.power, tolerance = 0.1)
    }

    @Test
    fun `the FA frame carries no temperature`() {
        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(telemetryFrame))
        assertTrue(telemetry.temperature.isNaN(), "FA frame must not fabricate a temperature")
    }

    @Test
    fun `the FB status frame adds temperature to the same telemetry`() {
        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(statusFrame))

        assertClose(166.62, telemetry.outputVoltage)
        assertClose(10.00, telemetry.outputCurrent)
        assertClose(50.49, telemetry.temperature)
        assertClose(223.0, telemetry.targetVoltage)
    }

    @Test
    fun `a later FA frame does not wipe the temperature learned from an FB frame`() {
        val engine = SkatChargerProtocolEngine()
        engine.consumeFrame(statusFrame)
        val afterTelemetry = assertNotNull(engine.consumeFrame(telemetryFrame))

        assertClose(50.49, afterTelemetry.temperature)
        assertClose(165.10, afterTelemetry.outputVoltage)
    }

    @Test
    fun `reassembly recovers frames split across notification chunks`() {
        val stream = telemetryFrame + statusFrame
        val engine = SkatChargerProtocolEngine()

        var last: SkatChargerTelemetry? = null
        // Feed one byte at a time - the worst case for a marker-plus-length reassembler.
        for (b in stream) {
            engine.consume(byteArrayOf(b))?.let { last = it }
        }
        val telemetry = assertNotNull(last)
        assertClose(166.62, telemetry.outputVoltage)
        assertClose(50.49, telemetry.temperature)
    }

    @Test
    fun `a marker byte inside float data does not manufacture a frame`() {
        // 0xFA followed by 24 bytes of arbitrary data whose 3rd float is not V*I.
        val noise = ByteArray(SKAT_TELEMETRY_FRAME_SIZE) { 0xFA.toByte() }
        assertNull(decodeSkatChargerFrame(noise))

        val result = reassembleSkatChargerFrames(noise)
        assertTrue(result.frames.isEmpty(), "power==V*I must reject arbitrary 0xFA runs")
    }

    @Test
    fun `a frame with impossible voltage is rejected even when power checks out`() {
        // A fragment stitched across a frame boundary can read a huge voltage with I=0, so
        // power == V*I trivially holds. Plausibility bounds must still throw it out.
        // FA + V=1e9 (0x4E6E6B28) + I=0 + P=0 + target=223 + Ah + Wh
        val frame = bytes("FA 28 6B 6E 4E 00 00 00 00 00 00 00 00 00 00 5F 43 E7 DC F7 40 6D E9 D7 44")
        assertNull(SkatChargerProtocolEngine().consumeFrame(frame))
    }

    @Test
    fun `an impossible temperature in an FB frame is dropped - not shown`() {
        // The real FB frame decodes; sabotage only the temperature float (offset 43) to a value
        // no charger reaches and it must come back NaN while the rest still decodes.
        val sabotaged = statusFrame.copyOf()
        // -1e9 little-endian at offset 43: 28 6B 6E CE
        val garbage = bytes("28 6B 6E CE")
        garbage.copyInto(sabotaged, destinationOffset = 43)
        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(sabotaged))
        assertTrue(telemetry.temperature.isNaN(), "an impossible temperature must be dropped")
        assertClose(166.62, telemetry.outputVoltage)
    }

    /**
     * Target/Ah/Wh sit past the three floats the `power == V * I` invariant vouches for, so a
     * frame can pass validation with a garbage tail. Now that these three are rider-facing
     * tiles and not just chart series, an out-of-range read has to come back unknown rather
     * than land on the dashboard as minus a billion.
     */
    @Test
    fun `an impossible session counter is dropped while the rest of the frame still decodes`() {
        val sabotaged = telemetryFrame.copyOf()
        // -1e9 little-endian (28 6B 6E CE) over target (offset 13), Ah (17) and Wh (21).
        val garbage = bytes("28 6B 6E CE")
        garbage.copyInto(sabotaged, destinationOffset = 13)
        garbage.copyInto(sabotaged, destinationOffset = 17)
        garbage.copyInto(sabotaged, destinationOffset = 21)

        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(sabotaged))
        assertTrue(telemetry.targetVoltage.isNaN(), "an impossible target voltage must be dropped")
        assertTrue(telemetry.ampHours.isNaN(), "impossible Ah must be dropped")
        assertTrue(telemetry.wattHours.isNaN(), "impossible Wh must be dropped")
        // The three floats the invariant does cover are untouched and still decode.
        assertClose(165.10, telemetry.outputVoltage)
        assertClose(10.00, telemetry.outputCurrent)
        assertClose(1651.02, telemetry.power)
    }

    @Test
    fun `a real frame's session counters pass the plausibility bounds untouched`() {
        val telemetry = assertNotNull(SkatChargerProtocolEngine().consumeFrame(telemetryFrame))
        assertClose(223.0, telemetry.targetVoltage)
        assertClose(7.7457, telemetry.ampHours, tolerance = 0.001)
        assertClose(1727.29, telemetry.wattHours)
    }

    @Test
    fun `the charger is never polled`() {
        assertTrue(SkatChargerProtocolEngine().pollingCommands().isEmpty())
    }

    // --- Charge-critical write commands, byte-for-byte against the vendor app's `an(...)` calls ---

    @Test
    fun `the value encoder matches the vendor ln 100x big-endian`() {
        // 173.40 V -> floor(17340) = 0x000043BC
        assertEquals("000043bc", encodeSkatSettingValue(173.40).toHexString())
        // 10.00 A -> floor(1000) = 0x000003E8
        assertEquals("000003e8", encodeSkatSettingValue(10.00).toHexString())
    }

    @Test
    fun `second stage voltage is F858 plus the value - then apply FF12`() {
        val commands = assertNotNull(skatChargeSettingCommands(SkatChargeSetting.SecondStageVoltage, 173.40))
        assertEquals(2, commands.size)
        assertEquals("f858000043bc", commands[0].toHexString())
        assertEquals("ff12", commands[1].toHexString())
    }

    @Test
    fun `second stage current is F857 plus the value - then apply FF12`() {
        val commands = assertNotNull(skatChargeSettingCommands(SkatChargeSetting.SecondStageCurrent, 10.00))
        assertEquals("f857000003e8", commands[0].toHexString())
        assertEquals("ff12", commands[1].toHexString())
    }

    @Test
    fun `max voltage limit applies with FF19 then FF14`() {
        val commands = assertNotNull(skatChargeSettingCommands(SkatChargeSetting.MaxVoltage, 225.00))
        assertEquals("f8fa000057e4", commands[0].toHexString()) // floor(22500) = 0x57E4
        assertEquals("ff19", commands[1].toHexString())
        assertEquals("ff14", commands[2].toHexString())
    }

    @Test
    fun `a value outside the vendor range yields no command`() {
        // Max current range is 10..400 A.
        assertNull(skatChargeSettingCommands(SkatChargeSetting.MaxCurrent, 500.0))
        assertNull(skatChargeSettingCommands(SkatChargeSetting.MaxCurrent, 1.0))
        assertNull(skatChargeSettingCommands(SkatChargeSetting.MaxCurrent, Double.NaN))
        // A value inside the range still builds.
        assertNotNull(skatChargeSettingCommands(SkatChargeSetting.MaxCurrent, 40.0))
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
