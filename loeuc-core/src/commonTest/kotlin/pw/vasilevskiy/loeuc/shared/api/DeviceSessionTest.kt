package pw.vasilevskiy.loeuc.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pw.vasilevskiy.loeuc.shared.protocol.NinebotAddress
import pw.vasilevskiy.loeuc.shared.protocol.NinebotFrameCodec
import pw.vasilevskiy.loeuc.shared.protocol.NinebotParam
import pw.vasilevskiy.loeuc.shared.protocol.NinebotCommand

class DeviceSessionTest {

    @Test
    fun inmotionLiveUpdateRetainsTheLatestBatterySnapshot() {
        val session = WheelSession.of(WheelProtocol.Inmotion)
        session.ingest("AAAA11088201020D0101010094".bytes(), 1L)

        val bms = session.ingest("AAAA141185CE580000000001001B020000000000000E".bytes(), 2L).single()
        val live = session.ingest(inmotionFrame(0x04, ByteArray(62)), 3L).single()

        assertEquals(bms.battery, live.battery)
        assertEquals(227.34, live.battery!!.packs.single().voltageV!!, 0.001)
    }

    @Test
    fun frameWindowCopiesBytesAndHonorsCapacity() {
        val window = FrameWindow(2)
        val first = byteArrayOf(1, 2)
        window.add(DeviceFrame(10L, "notify", "FFE1", first))
        first[0] = 9
        assertEquals(1, window.snapshot().first().bytes[0])
        window.add(DeviceFrame(20L, "notify", "FFE1", byteArrayOf(3)))
        window.add(DeviceFrame(30L, "notify", "FFE1", byteArrayOf(4)))

        assertEquals(listOf(20L, 30L), window.snapshot().map { it.timestampMs })
        assertEquals(3, window.snapshot().first().bytes[0])
    }

    @Test
    fun wheelCommandsExposeReadsAndExplicitUntestedWrites() {
        val commands = WheelSession.of(WheelProtocol.Ninebot).commands

        assertTrue(commands.initialReads.isNotEmpty())
        assertTrue(commands.telemetryPoll != null)
        assertTrue(commands.bmsReads.isNotEmpty())
        val write = commands.settingWrite("ninebot_tail_light", 1, currentWord = 0b10101)
        assertEquals(CommandStatus.UNTESTED, write.validation.status)
        assertEquals("5aa5023e1403d31700befe", write.bytes.toHex())
    }

    @Test
    fun facadeExposesEveryNinebotWritableCatalogKeyAsUntested() {
        val commands = WheelSession.of(WheelProtocol.Ninebot).commands
        val writableKeys = listOf(
            "ninebot_lock_mode",
            "ninebot_limited_mode",
            "ninebot_limited_speed",
            "ninebot_alarm_1_enabled",
            "ninebot_alarm_2_enabled",
            "ninebot_alarm_3_enabled",
            "ninebot_alarm_1_speed",
            "ninebot_alarm_2_speed",
            "ninebot_alarm_3_speed",
            "ninebot_led_mode",
            "ninebot_led_color_1",
            "ninebot_led_color_2",
            "ninebot_led_color_3",
            "ninebot_led_color_4",
            "ninebot_pedal_sensitivity",
            "ninebot_headlight",
            "ninebot_drl",
            "ninebot_tail_light",
            "ninebot_handle_button",
            "ninebot_brake_assist",
            "ninebot_speaker_volume",
        )

        writableKeys.forEach { key ->
            val currentWord = if (key.contains("enabled") || key in setOf(
                "ninebot_headlight", "ninebot_drl", "ninebot_tail_light",
                "ninebot_handle_button", "ninebot_brake_assist",
            )) 0 else null
            val command = commands.settingWrite(key, 1, currentWord)
            assertEquals(CommandStatus.UNTESTED, command.validation.status, key)
            assertEquals(WheelProtocol.Ninebot, command.protocol, key)
        }
    }

    @Test
    fun sessionPublishesNinebotDiagnosticsAndSettings() {
        val session = WheelSession.of(WheelProtocol.Ninebot)
        val firmware = NinebotFrameCodec.build(
            destination = NinebotAddress.App,
            command = NinebotCommand.Read,
            param = NinebotParam.Firmware,
            data = byteArrayOf(0x25, 0x01, 0x0C, 0, 0, 0),
            source = NinebotAddress.Controller,
        )
        val lockBlock = ByteArray(32).apply {
            this[4] = 1
            this[8] = 0xC4.toByte()
            this[9] = 0x09
        }
        val settings = NinebotFrameCodec.build(
            destination = NinebotAddress.App,
            command = NinebotCommand.Read,
            param = NinebotParam.LockMode,
            data = lockBlock,
            source = NinebotAddress.Controller,
        )

        val diagnosticUpdate = session.ingest(firmware, 1L).single()
        assertEquals(listOf(12), diagnosticUpdate.diagnostics?.codes)

        val settingsUpdate = session.ingest(settings, 2L).single()
        val speed = settingsUpdate.settings.single { it.key == "ninebot_limited_speed" }
        assertEquals(25, speed.value)
        assertTrue(speed.writable)
        assertNotNull(settingsUpdate.diagnostics)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.bytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun inmotionFrame(command: Int, data: ByteArray): ByteArray {
        val payload = byteArrayOf(0x14, (data.size + 1).toByte(), (command or 0x80).toByte()) + data
        val checksum = payload.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }.toByte()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload + checksum
    }

    @Test
    fun emptyInputDoesNotInventAnUpdate() {
        val session = WheelSession.of(WheelProtocol.Ninebot)

        assertEquals(emptyList(), session.ingest(byteArrayOf(), 123L))
        assertEquals(null, session.latest)
    }

    @Test
    fun eachWheelExposesOnlyCommandsProvidedByItsEngine() {
        assertTrue(WheelSession.of(WheelProtocol.KingSong).commands.initialReads.isNotEmpty())
        assertTrue(WheelSession.of(WheelProtocol.Inmotion).commands.initialReads.isNotEmpty())
        assertTrue(WheelSession.of(WheelProtocol.Begode).commands.initialReads.isEmpty())
        assertTrue(WheelSession.of(WheelProtocol.LeaperKim).commands.bmsReads.isEmpty())
        assertTrue(WheelSession.of(WheelProtocol.Nosfet).commands.telemetryPoll == null)
        assertTrue(WheelSession.of(WheelProtocol.SolowheelXtreme).commands.initialReads.isEmpty())
    }

    @Test
    fun frameEmissionAndResetAreObservable() {
        val session = WheelSession.of(
            WheelProtocol.Ninebot,
            SessionOptions(emitFrames = true, frameWindowCapacity = 2),
        )

        assertEquals(emptyList(), session.ingest(byteArrayOf(1, 2), 10L, "FFE1"))
        assertEquals(null, session.latest)
        session.reset()
        assertEquals(null, session.latest)
        assertEquals(emptyList(), session.ingest(byteArrayOf(), 11L))
    }

    @Test
    fun batteryStatisticsIgnoreMissingCells() {
        val battery = DeviceBatterySnapshot(
            listOf(DeviceBatteryPack(1, cells = listOf(4.0, 4.2), temperaturesC = listOf(30.0))),
        )

        assertEquals(4.1, battery.cellVoltageAverageV!!, absoluteTolerance = 1e-9)
        assertEquals(0.2, battery.cellVoltageDeltaV!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun chargerPollsMatchTransportStyle() {
        val hw = ChargerSession.of(ChargerProtocol.HwSmart)
        val skat = ChargerSession.of(ChargerProtocol.SkatCanControl)

        assertTrue(hw.pollCommands().isNotEmpty())
        assertTrue(skat.pollCommands().isEmpty())
        assertEquals(null, hw.ingest(byteArrayOf(), 1L))
        hw.reset()
        skat.reset()
    }
}
