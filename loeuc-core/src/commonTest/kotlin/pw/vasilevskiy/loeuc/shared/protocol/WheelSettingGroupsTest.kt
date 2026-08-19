package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
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
        )
        assertEquals(pinned.indices.toList(), pinned.map { WheelSettingGroups.pinRank(it) })
        assertTrue(
            listOf("angle_trim", "inmotion_pitch_zero", "ninebot_brake_assist").all {
                WheelSettingGroups.pinRank(it) > WheelSettingGroups.pinRank("pedal_hardness")
            },
        )
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
