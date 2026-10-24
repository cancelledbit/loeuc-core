package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import pw.vasilevskiy.loeuc.shared.api.InmotionLegacySettingKeys
import pw.vasilevskiy.loeuc.shared.api.toDeviceSettingCapabilities
import kotlin.test.assertTrue

class WheelSettingGroupsTest {
    @Test
    fun everyDecodedP6SettingLandsInANamedSection() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA11088201020D0101010094".bytes())
        engine.consume(
            """
            AAAA1434A0200000000001000000983A983AB80B401F401F34216400403800282814030A28
            646428D0073232384A01325F1E1E06313C0E2EE4
            """.bytes(),
        )

        val grouped = engine.settingsCapabilities().groupBy { WheelSettingGroups.of(it.key) }
        // Nothing from a decoded block may fall through to "Other": that bucket is for
        // keys nobody has placed yet, and a P6 has none left.
        assertEquals(
            emptyList(),
            grouped[WheelSettingGroups.Other].orEmpty().map { it.key },
        )
        assertTrue(grouped.getValue(WheelSettingGroups.Ride).any { it.key == "inmotion_ride_mode" })
        assertTrue(grouped.getValue(WheelSettingGroups.Speed).any { it.key == "inmotion_speed_limit" })
        assertTrue(grouped.getValue(WheelSettingGroups.Light).any { it.key == "inmotion_auto_light_state" })
        assertTrue(grouped.getValue(WheelSettingGroups.Sound).any { it.key == "inmotion_voice_volume" })
        assertTrue(grouped.getValue(WheelSettingGroups.Battery).any { it.key == "inmotion_charge_cut_off_percent" })
        assertTrue(grouped.getValue(WheelSettingGroups.Safety).any { it.key == "inmotion_lock_mode" })
    }

    @Test
    fun everyLegacyInmotionSettingLandsInItsSection() {
        // Real V8F slow info: every one of the eight read-only settings is present.
        val settings = assertNotNull(
            InmotionLegacyDecoder.decodeSettings(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8fSlow)),
        ).toDeviceSettingCapabilities()
        assertEquals(InmotionLegacySettingKeys.all.toSet(), settings.map { it.key }.toSet())

        val expected = mapOf(
            "inmotion_legacy_max_speed" to WheelSettingGroups.Speed,
            "inmotion_legacy_pedal_hardness" to WheelSettingGroups.Ride,
            "inmotion_legacy_pedal_tilt" to WheelSettingGroups.Ride,
            "inmotion_legacy_ride_mode" to WheelSettingGroups.Ride,
            "inmotion_legacy_headlight" to WheelSettingGroups.Light,
            "inmotion_legacy_decorative_light" to WheelSettingGroups.Light,
            "inmotion_legacy_speaker_volume" to WheelSettingGroups.Sound,
            "inmotion_legacy_handle_button" to WheelSettingGroups.Safety,
        )
        assertEquals(expected, settings.associate { it.key to WheelSettingGroups.of(it.key) })
    }

    @Test
    fun pedalStiffnessLeadsTheRideSectionOnEveryBrand() {
        // LeaperKim used to file it under "Other" - it is a control setting, and the one a
        // rider changes most often, so it belongs at the head of the first section.
        assertEquals(WheelSettingGroups.Ride, WheelSettingGroups.of("pedal_hardness"))
        assertEquals(0, WheelSettingGroups.Ride.order)

        val pinned = listOf(
            "pedal_hardness",
            "inmotion_ride_mode",
            "inmotion_pedal_sensitivity_1",
            "inmotion_pedal_sensitivity_2",
            "ninebot_pedal_sensitivity",
            "inmotion_legacy_pedal_hardness",
        )
        assertEquals(pinned.indices.toList(), pinned.map { WheelSettingGroups.pinRank(it) })
        assertTrue(
            listOf("angle_trim", "inmotion_pitch_zero", "ninebot_brake_assist").all {
                WheelSettingGroups.pinRank(it) > WheelSettingGroups.pinRank("pedal_hardness")
            },
        )
    }

    /**
     * Every LeaperKim setting has a section of its own.
     *
     * The brand's keys carry no brand prefix - that is how they arrived from the protocol -
     * so they used to fall through the `when` into [WheelSettingGroups.Other]: two of its
     * seventeen settings sat under "Ride feel" and the remaining fifteen in "Other", which
     * is not a layout but the absence of one.
     */
    @Test
    fun everyLeaperKimSettingHasItsOwnSection() {
        val placement = mapOf(
            "pedal_hardness" to WheelSettingGroups.Ride,
            "angle_trim" to WheelSettingGroups.Ride,
            "acc_dec_helper" to WheelSettingGroups.Ride,
            "acc_reduction" to WheelSettingGroups.Ride,
            "stop_speed" to WheelSettingGroups.Speed,
            "stop_power" to WheelSettingGroups.Speed,
            "speed_alarm" to WheelSettingGroups.Speed,
            "high_speed_mode" to WheelSettingGroups.Speed,
            "screen_backlight" to WheelSettingGroups.Light,
            "key_tone" to WheelSettingGroups.Sound,
            "max_charge_voltage" to WheelSettingGroups.Battery,
            "low_voltage_mode" to WheelSettingGroups.Battery,
            "voltage_correction" to WheelSettingGroups.Battery,
            "brake_overpressure_alarm" to WheelSettingGroups.Safety,
            "gyro_calibration" to WheelSettingGroups.Safety,
            "transport_mode" to WheelSettingGroups.System,
            "unit" to WheelSettingGroups.System,
        )
        placement.forEach { (key, group) ->
            assertEquals(group, WheelSettingGroups.of(key), "key=$key")
        }
        // Nothing of the brand is left in the catch-all.
        assertTrue(placement.keys.none { WheelSettingGroups.of(it) == WheelSettingGroups.Other })
        // Pedal stiffness still leads the screen, as it does on every other brand.
        assertEquals(0, WheelSettingGroups.pinRank("pedal_hardness"))
    }

    @Test
    fun rawByteRowsSortLastAndGroupsAreOrdered() {
        assertEquals(WheelSettingGroups.Raw, WheelSettingGroups.of("inmotion_setting_byte_17"))
        assertEquals(WheelSettingGroups.Raw, WheelSettingGroups.of("inmotion_settings_payload_length"))
        assertEquals(WheelSettingGroups.Other, WheelSettingGroups.of("something_nobody_placed_yet"))
        assertEquals(WheelSettingGroups.System, WheelSettingGroups.of("inmotion_standby_time"))
        assertEquals(
            WheelSettingGroups.all.map { it.order },
            WheelSettingGroups.all.indices.toList(),
        )
        assertEquals(WheelSettingGroups.all.size, WheelSettingGroups.all.map { it.id }.toSet().size)
    }
}

private fun String.bytes(): ByteArray =
    filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
