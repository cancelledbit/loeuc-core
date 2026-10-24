package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmotionDiagnosticsReportTest {
    /** Slot 1: 83.73 V, discharging 1.20 A, detected + enabled. Slot 2: absent, stale bytes. */
    private val batteryPayload = byteArrayOf(
        0xB5.toByte(), 0x20, 0x00, 0x00, 0x78, 0x00, 0x03, 0x00,
        0x1E, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04,
    )

    /** Error code 0x00000010, then flag bytes: field 4 "Battery fault" is bit 4 of byte 0. */
    private val errorPayload = byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun engineListsEveryBatterySlotOfTheLastAnswer() {
        val engine = InmotionProtocolEngine()
        assertTrue(engine.batteryDiagnostics().isEmpty())

        engine.consume(frame(0x14, 0x05, batteryPayload))

        val batteries = engine.batteryDiagnostics()
        assertEquals(2, batteries.size)
        assertEquals(1, batteries[0].index)
        assertTrue(batteries[0].detected)
        assertTrue(batteries[0].enabled)
        assertEquals(83.73, batteries[0].voltage, 0.0001)
        assertEquals(1.2, batteries[0].dischargeCurrent, 0.0001)
        assertEquals(false, batteries[0].hasFault)
        assertEquals("B5 20 00 00 78 00 03 00", batteries[0].hexData)
        // The absent slot stays in the list: that it is absent is what the screen reports.
        assertEquals(false, batteries[1].detected)
        assertTrue(batteries[1].hasFault)

        engine.reset()
        assertTrue(engine.batteryDiagnostics().isEmpty())
    }

    @Test
    fun thereIsNoReportBeforeTheWheelAnsweredTheErrorRead() {
        val engine = InmotionProtocolEngine()
        engine.consume(frame(0x14, 0x05, batteryPayload))

        assertNull(engine.diagnosticsReportText("P6", "AA:BB", 1L))
    }

    @Test
    fun reportQuotesTheFramesAndSpellsOutTheBatteryBits() {
        val engine = InmotionProtocolEngine()
        val errorFrame = frame(0x14, 0x03, errorPayload)
        engine.consume(errorFrame)
        engine.consume(frame(0x14, 0x05, batteryPayload))

        val text = assertNotNull(engine.diagnosticsReportText("P6-1234", "AA:BB:CC", 1_700_000_000_000L))
        val lines = text.lines()

        assertEquals("LoEUC Inmotion P6 diagnostics", lines[0])
        assertEquals("Device: P6-1234", lines[1])
        assertEquals("Address: AA:BB:CC", lines[2])
        assertEquals("Timestamp: 1700000000000", lines[3])
        assertEquals("Command: 0x03", lines[4])
        assertEquals("General error code: 0x00000010 (16)", lines[5])
        assertEquals("Payload length: 7", lines[6])
        assertTrue("Decoded active items (1), APK-derived V14/P6 mapping:" in lines, text)
        assertTrue(
            "- [Error] Battery: Battery fault (field=4, payload@0, bit=4, raw=1)" in lines,
            text,
        )
        assertTrue("Battery slots (2), command 0x05:" in lines, text)
        assertTrue(
            "- battery=1 detected=true enabled=true charging=false voltageRaw=8373 " +
                "chargeCurrentRaw=0 dischargeCurrentRaw=120 fault=false logic=false signal=false " +
                "voltageSensor=false currentSensor=false cannotCharge=false warning=false " +
                "protection=false error=false raw=B5 20 00 00 78 00 03 00" in lines,
            text,
        )
        assertTrue(lines.any { it.startsWith("- battery=2 detected=false") && "cannotCharge=true" in it }, text)
        assertTrue("10 00 00 00 00 00 00" in lines, text)
        assertTrue(errorFrame.joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') } in lines)
        assertTrue("- no direct BMS responses captured" in lines, text)
    }

    @Test
    fun emptyReportSaysWhatIsMissingInsteadOfLeavingBlanks() {
        val text = InmotionDiagnosticsReport.text(
            deviceName = "P6",
            deviceAddress = "AA",
            timestampMillis = 5,
            command = 3,
            errorCode = null,
            payload = byteArrayOf(),
            rawFrame = byteArrayOf(),
            items = emptyList(),
            batteryPayload = null,
            batteryRawFrame = null,
            directReads = listOf(
                InmotionDiagnosticsReportDirectRead(0x31, 0x17, 2, 900L, byteArrayOf(1, 0xAB.toByte()), byteArrayOf(0x0F)),
            ),
        )
        val lines = text.lines()

        assertTrue("General error code: unavailable" in lines)
        assertTrue("- none" in lines)
        assertTrue("- no battery response captured" in lines)
        assertTrue("Direct BMS read responses (1):" in lines)
        assertTrue("- source=0x31 target=0x17 selector=2 timestamp=900 payload=01 AB" in lines, text)
        assertTrue("  frame=0F" in lines)
        assertEquals(2, lines.count { it == "unavailable" })
    }

    @Test
    fun fileNameDropsTheSeparatorsOfEitherAddressForm() {
        assertEquals(
            "loeuc_AABBCCDDEE_p6_diagnostics_20260730_184247.txt",
            InmotionDiagnosticsReport.fileName("AA:BB:CC:DD:EE", "20260730_184247"),
        )
        assertEquals(
            "loeuc_0A1B2C3D_p6_diagnostics_x.txt",
            InmotionDiagnosticsReport.fileName("0A1B-2C3D", "x"),
        )
    }

    private fun frame(flag: Int, command: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf((command or 0x80).toByte()) + data
        val payload = byteArrayOf(flag.toByte(), body.size.toByte()) + body
        val checksum = payload.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }.toByte()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload + byteArrayOf(checksum)
    }
}
