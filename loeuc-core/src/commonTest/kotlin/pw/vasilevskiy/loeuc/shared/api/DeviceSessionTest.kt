package pw.vasilevskiy.loeuc.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import pw.vasilevskiy.loeuc.shared.protocol.BegodeFirmwareFamily
import pw.vasilevskiy.loeuc.shared.protocol.BegodeSettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyCaptures
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyCommands
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsReads
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsWriter
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyRequests
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

    /** The first 914 ms of a real V8S connection, fed through the public session as notifies. */
    @Test
    fun inmotionLegacySessionDecodesARealV8sConnection() {
        val session = WheelSession.of(WheelProtocol.InmotionLegacy)
        val updates = InmotionLegacyCaptures.v8sConnect.flatMap { (time, hex) -> session.ingest(hex.bytes(), time) }

        // Three hellos, two PIN replies, two slow info replies, one fast info reply.
        assertEquals(8, updates.size)
        assertTrue(updates.all { it.protocol == WheelProtocol.InmotionLegacy && it.brand is WheelBrandUpdate.InmotionLegacy })
        val telemetry = session.telemetry
        assertEquals(81.98, telemetry[DeviceMetric.PackVoltageV]!!, 1e-9)
        assertEquals(0.07, telemetry[DeviceMetric.BatteryCurrentA]!!, 1e-9)
        assertEquals(30.0, telemetry[DeviceMetric.ControllerTemperatureC])
        assertEquals(96.0, telemetry[DeviceMetric.BatteryPercent])
        assertEquals(1.199, telemetry[DeviceMetric.TotalDistanceKm]!!, 1e-9)
        // -110 on the wire is no probe, and V8S has no roll reading: both stay absent.
        assertNull(telemetry[DeviceMetric.MosTemperatureC])
        assertNull(telemetry[DeviceMetric.RollDeg])
        assertEquals(81.98, session.latest!!.snapshot.getValue(pw.vasilevskiy.loeuc.shared.alerts.model.MetricId.VOLTAGE_V)!!, 1e-9)

        val settings = session.latest!!.settings.associateBy { it.key }
        assertEquals(8, settings.size)
        // A V8S reports every one of them back, so every one can be written.
        assertTrue(settings.values.all { it.writable })
        assertEquals(35, settings.getValue("inmotion_legacy_max_speed").value)
        assertEquals(80, settings.getValue("inmotion_legacy_pedal_tilt").value)
        assertEquals(1, settings.getValue("inmotion_legacy_ride_mode").value)
        assertEquals(1, settings.getValue("inmotion_legacy_handle_button").value)
        assertEquals(InmotionLegacySettingKeys.all.toSet(), settings.keys)

        // Identity arrived, so the session has moved on to fast info.
        assertContentEquals(InmotionLegacyRequests.fastInfo(), session.nextRequest(915))

        session.reset()
        assertNull(session.latest)
        assertContentEquals(InmotionLegacyRequests.pin(), session.nextRequest(1_000))
    }

    @Test
    fun inmotionLegacyRequestsComeFromTheSessionAndOtherProtocolsHaveNone() {
        val session = WheelSession.of(WheelProtocol.InmotionLegacy)
        assertContentEquals(InmotionLegacyRequests.pin(), session.nextRequest(0))
        // Not due before the 250 ms slot, and a failed write is retried 125 ms later.
        assertNull(session.nextRequest(100))
        session.onWriteFailed(100)
        assertNull(session.nextRequest(224))
        assertContentEquals(InmotionLegacyRequests.pin(), session.nextRequest(225))

        val commands = session.commands
        assertEquals(2, commands.initialReads.size)
        assertContentEquals(InmotionLegacyRequests.pin(), commands.initialReads[0])
        assertContentEquals(InmotionLegacyRequests.slowInfo(), commands.initialReads[1])
        assertContentEquals(InmotionLegacyRequests.fastInfo(), commands.telemetryPoll)
        assertTrue(commands.bmsReads.isEmpty())

        WheelProtocol.entries.filter { it != WheelProtocol.InmotionLegacy }.forEach { protocol ->
            assertNull(WheelSession.of(protocol).nextRequest(0), protocol.name)
        }
        // A V2 InMotion frame is not a legacy message and must not invent an update.
        assertEquals(emptyList(), session.ingest("AAAA11088201020D0101010094".bytes(), 1L))
    }

    @Test
    fun inmotionLegacySettingWriteIsQueuedAndGoesOutThroughNextRequest() {
        val session = WheelSession.of(WheelProtocol.InmotionLegacy)
        // Before slow info the model is unknown, so nothing is built.
        assertTrue(session.writeSetting(InmotionLegacySettingKeys.Headlight, 1) is SettingWriteResult.Rejected)

        InmotionLegacyCaptures.v8sConnect.forEach { (time, hex) -> session.ingest(hex.bytes(), time) }
        assertEquals("V8S", session.inmotionLegacyIdentity?.model?.displayName)
        assertEquals(InmotionLegacySettingKeys.all, session.inmotionLegacySettingRows().map { it.key })

        val queued = assertIs<SettingWriteResult.Queued>(session.writeSetting(InmotionLegacySettingKeys.Headlight, 1))
        assertEquals(CommandStatus.UNTESTED, queued.command.validation.status)
        assertContentEquals(InmotionLegacyCommands.headlight(on = true), queued.command.bytes)
        // Out of the model's range: rejected, and nothing extra is queued.
        assertTrue(session.writeSetting(InmotionLegacySettingKeys.MaxSpeed, 46) is SettingWriteResult.Rejected)

        // The command leaves at the next slot, then slow info is re-read for the wheel's own value.
        assertContentEquals(InmotionLegacyCommands.headlight(on = true), session.nextRequest(915))
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(1_200))
    }

    /** Verbatim S22 answers: alarms, name, live frame, ride stats. */
    @Test
    fun kingSongSessionExposesIdentitySettingsAndWrites() {
        val session = WheelSession.of(WheelProtocol.KingSong)
        // No alarm answer yet: restating the other three speeds would zero them.
        assertTrue(session.writeSetting(KingSongSettingsCatalog.MaxSpeedKey, 50) is SettingWriteResult.Rejected)

        val updates = listOf(
            "AA5500003200370038003C0000000000B5145A5A",
            "AA554B532D5332322D30323337000000BB1484FD",
            "AA55E42A79067900D4762A00F00A01E0A9145A5A",
            "AA550000A103E7045D0B120100006009B9145A5A",
        ).flatMap { session.ingest(it.bytes(), 1L) }

        assertEquals("KS-S22", session.kingSongIdentity?.model)
        assertEquals(60, session.kingSongSettings?.maxSpeedKmh)
        val maxSpeed = updates.last().settings.single { it.key == KingSongSettingsCatalog.MaxSpeedKey }
        assertEquals(60, maxSpeed.value)
        assertTrue(maxSpeed.writable)
        assertEquals(session.kingSongSettingRows().map { it.key }, updates.last().settings.map { it.key })

        val write = assertIs<SettingWriteResult.Send>(session.writeSetting(KingSongSettingsCatalog.MaxSpeedKey, 58))
        assertContentEquals(KingSongSettingsWriter.speedAlarms(50, 55, 56, 58), write.command.bytes)
        assertContentEquals(KingSongSettingsReads.speedAlarms(), write.readbacks.single())
        assertTrue(session.writeSetting(KingSongSettingsCatalog.Alarm1SpeedKey, 57) is SettingWriteResult.Rejected)
        assertTrue(session.writeSetting("inmotion_legacy_headlight", 1) is SettingWriteResult.Rejected)
        assertNull(session.begodeFirmware)
        assertNull(session.inmotionLegacySettings)
    }

    @Test
    fun begodeSessionNamesTheFirmwareAndPublishesItsSettingsRows() {
        val session = WheelSession.of(WheelProtocol.Begode)
        assertNull(session.begodeFirmware)

        val update = session.ingest("GW2002001".encodeToByteArray(), 1L).single()

        assertEquals(BegodeFirmwareFamily.Begode, session.begodeFirmware?.family)
        assertEquals("GW2002001", session.begodeFirmware?.label)
        val settings = update.settings.associateBy { it.key }
        assertEquals(session.begodeSettingRows().map { it.key }, update.settings.map { it.key })
        assertTrue(settings.getValue(BegodeSettingsCatalog.TiltbackSpeedKey).writable)
        assertEquals(false, settings.getValue(BegodeSettingsCatalog.FirmwareKey).writable)
        // Begode writes are step sequences with pauses; the session does not build them.
        assertTrue(session.writeSetting(BegodeSettingsCatalog.TiltbackSpeedKey, 40) is SettingWriteResult.Rejected)
        assertNull(session.kingSongIdentity)
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
