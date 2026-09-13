package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.InmotionLegacySettingKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rows and writes over real slow info replies from WheelLog's captures. */
class InmotionLegacySettingsCatalogTest {
    private class Reply(val identity: InmotionLegacyIdentity?, val settings: InmotionLegacySettings)

    private fun reply(chunks: List<String>): Reply = reply(InmotionLegacyCaptures.message(chunks))

    private fun reply(message: InmotionLegacyMessage) =
        Reply(InmotionLegacyDecoder.decodeIdentity(message), assertNotNull(InmotionLegacyDecoder.decodeSettings(message)))

    @Test
    fun v8sRowsCarryTheWheelsValuesAndTheModelsRanges() {
        val v8s = reply(InmotionLegacyCaptures.v8sSlow)
        val rows = InmotionLegacySettingsCatalog.capabilities(v8s.identity, v8s.settings)

        assertEquals(InmotionLegacySettingKeys.all, rows.map { it.key })
        val byKey = rows.associateBy { it.key }
        fun row(key: String) = byKey.getValue(key)

        val maxSpeed = row(InmotionLegacySettingKeys.MaxSpeed)
        assertEquals(35, maxSpeed.rawValue)
        assertEquals("35 km/h", maxSpeed.valueTextEn)
        assertEquals("35 км/ч", maxSpeed.valueTextRu)
        assertEquals(3 to 45, maxSpeed.minValue to maxSpeed.maxValue)
        assertEquals("slider", maxSpeed.kind)
        assertEquals(InmotionLegacyCommands.RIDE_SETTINGS_ID, maxSpeed.commandId)

        assertEquals(80, row(InmotionLegacySettingKeys.PedalTilt).rawValue)
        assertEquals("8.0°", row(InmotionLegacySettingKeys.PedalTilt).valueTextEn)
        assertEquals(-80 to 80, row(InmotionLegacySettingKeys.PedalTilt).let { it.minValue to it.maxValue })

        val rideMode = row(InmotionLegacySettingKeys.RideMode)
        assertEquals("choice", rideMode.kind)
        assertEquals("Classic" to "Классика", rideMode.valueTextEn to rideMode.valueTextRu)
        assertEquals(listOf(0, 1), rideMode.options.map { it.value })

        assertEquals("toggle", row(InmotionLegacySettingKeys.Headlight).kind)
        assertEquals(InmotionLegacyCommands.HEADLIGHT_ID, row(InmotionLegacySettingKeys.Headlight).commandId)
        assertEquals(1, row(InmotionLegacySettingKeys.HandleButton).rawValue)
        assertEquals("ON", row(InmotionLegacySettingKeys.HandleButton).valueTextEn)
        assertEquals(4 to 100, row(InmotionLegacySettingKeys.PedalHardness).let { it.minValue to it.maxValue })
        assertEquals(0 to 100, row(InmotionLegacySettingKeys.SpeakerVolume).let { it.minValue to it.maxValue })
        assertTrue(rows.all { it.commandId != null && it.titleRu.isNotEmpty() })
    }

    @Test
    fun aShortV5fReplyGivesOnlyTheRowsItCarries() {
        val v5f = reply(InmotionLegacyCaptures.v5fSlow)
        val rows = InmotionLegacySettingsCatalog.capabilities(v5f.identity, v5f.settings)

        assertEquals(
            listOf(InmotionLegacySettingKeys.MaxSpeed, InmotionLegacySettingKeys.PedalTilt, InmotionLegacySettingKeys.Headlight),
            rows.map { it.key },
        )
        assertEquals(25, rows.first().maxValue)
        // V5F has no decorative LED, and its payload would not report one back anyway.
        assertIs<InmotionLegacySettingWrite.Rejected>(
            InmotionLegacySettingsCatalog.write(InmotionLegacySettingKeys.DecorativeLight, 1, v5f.identity, v5f.settings),
        )
        // V5F has a speaker, but its slow info does not reach the volume field.
        assertIs<InmotionLegacySettingWrite.Rejected>(
            InmotionLegacySettingsCatalog.write(InmotionLegacySettingKeys.SpeakerVolume, 50, v5f.identity, v5f.settings),
        )
        assertIs<InmotionLegacySettingWrite.Command>(
            InmotionLegacySettingsCatalog.write(InmotionLegacySettingKeys.MaxSpeed, 25, v5f.identity, v5f.settings),
        )
        assertIs<InmotionLegacySettingWrite.Rejected>(
            InmotionLegacySettingsCatalog.write(InmotionLegacySettingKeys.MaxSpeed, 26, v5f.identity, v5f.settings),
        )
    }

    @Test
    fun anUnknownModelGetsReadOnlyRowsAndNoWrites() {
        val unknown = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8fSlow).let { real ->
            val extra = real.extra!!.copyOf().also { it[104] = 99 }
            reply(InmotionLegacyMessage(real.id, real.data, real.length, real.channel, real.format, real.type, extra))
        }
        val rows = InmotionLegacySettingsCatalog.capabilities(unknown.identity, unknown.settings)

        assertEquals(InmotionLegacySettingKeys.all, rows.map { it.key })
        assertTrue(rows.all { it.commandId == null && it.kind == KIND_READONLY })
        assertIs<InmotionLegacySettingWrite.Rejected>(
            InmotionLegacySettingsCatalog.write(InmotionLegacySettingKeys.Headlight, 1, unknown.identity, unknown.settings),
        )
        assertIs<InmotionLegacySettingWrite.Rejected>(
            InmotionLegacySettingsCatalog.write(InmotionLegacySettingKeys.Headlight, 1, null, null),
        )
    }

    @Test
    fun writesAreTheReferenceVectorsAndEveryRowsRangeIsExact() {
        val v8s = reply(InmotionLegacyCaptures.v8sSlow)
        fun write(key: String, value: Int) = InmotionLegacySettingsCatalog.write(key, value, v8s.identity, v8s.settings)
        fun bytes(key: String, value: Int) = assertIs<InmotionLegacySettingWrite.Command>(write(key, value)).bytes

        // WheelLog's command vectors.
        assertContentEquals("aaaa1501a5550f01000000b888000008050000c85555".decodeHex(), bytes(InmotionLegacySettingKeys.MaxSpeed, 35))
        assertContentEquals("aaaa1501a5550f0000000000000800080500008f5555".decodeHex(), bytes(InmotionLegacySettingKeys.PedalTilt, 80))
        assertContentEquals("aaaa0a06a5550f7c1500000000000008050000125555".decodeHex(), bytes(InmotionLegacySettingKeys.SpeakerVolume, 55))
        assertContentEquals("aaaa2E01a5550f010000000000000008050000a15555".decodeHex(), bytes(InmotionLegacySettingKeys.HandleButton, 1))

        for (row in InmotionLegacySettingsCatalog.capabilities(v8s.identity, v8s.settings)) {
            val setting = assertNotNull(InmotionLegacySettingsCatalog.settingFor(row.key))
            assertEquals(row.key, InmotionLegacySettingsCatalog.keyFor(setting))
            assertContentEquals(
                InmotionLegacyCommands.build(setting, row.maxValue, InmotionLegacyModel.V8S),
                bytes(row.key, row.maxValue),
                row.key,
            )
            assertIs<InmotionLegacySettingWrite.Command>(write(row.key, row.minValue), row.key)
            assertIs<InmotionLegacySettingWrite.Rejected>(write(row.key, row.minValue - 1), row.key)
            assertIs<InmotionLegacySettingWrite.Rejected>(write(row.key, row.maxValue + 1), row.key)
        }
        assertIs<InmotionLegacySettingWrite.Rejected>(write("inmotion_calibration", 1))
        assertContentEquals(InmotionLegacyRequests.slowInfo(), InmotionLegacySettingsCatalog.readsFor(null).single())
    }
}
