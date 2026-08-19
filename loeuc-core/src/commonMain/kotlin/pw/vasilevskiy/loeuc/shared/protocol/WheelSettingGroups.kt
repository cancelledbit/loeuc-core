package pw.vasilevskiy.loeuc.shared.protocol

/**
 * A section of the wheel hardware-settings screen.
 *
 * The screen used to be one flat list sorted by "editable sliders, then editable toggles,
 * then the rest, each alphabetically". That reads fine for a wheel with five settings and
 * badly for a P6, whose block decodes into thirty-five - auto-light thresholds landed
 * between a pedal-sensitivity slider and a charge limit. Grouping is by key, so it is the
 * same on both platforms and a brand that shares a key lands in the same section.
 */
data class WheelSettingGroup(
    val id: String,
    val order: Int,
    val titleEn: String,
    val titleRu: String,
)

object WheelSettingGroups {
    val Ride = WheelSettingGroup("ride", 0, "Ride feel", "Управление")
    val Speed = WheelSettingGroup("speed", 1, "Speed and limits", "Скорость и пороги")
    val Light = WheelSettingGroup("light", 2, "Lighting", "Свет")
    val Sound = WheelSettingGroup("sound", 3, "Sound", "Звук")
    val Battery = WheelSettingGroup("battery", 4, "Battery and charging", "Батарея и зарядка")
    val Safety = WheelSettingGroup("safety", 5, "Sensors and protection", "Датчики и защита")
    val System = WheelSettingGroup("system", 6, "System", "Система")
    val Other = WheelSettingGroup("other", 7, "Other", "Прочее")
    val Raw = WheelSettingGroup("raw", 8, "Raw block", "Сырые байты блока")

    val all: List<WheelSettingGroup> =
        listOf(Ride, Speed, Light, Sound, Battery, Safety, System, Other, Raw)

    /**
     * Keys pinned to the head of their section, in this order.
     *
     * Pedal stiffness is the setting a rider reaches for first and changes most often, so it
     * leads the screen on every brand instead of landing wherever "editable sliders first,
     * then alphabetically" happens to put it - on a LeaperKim that was below `angle_trim`,
     * and on a V12, whose pedal mode carries no write spec, below every editable row.
     */
    private val pinnedKeys: List<String> = listOf(
        "pedal_hardness",
        "inmotion_ride_mode",
        "inmotion_pedal_sensitivity_1",
        "inmotion_pedal_sensitivity_2",
        "ninebot_pedal_sensitivity",
    )

    /**
     * Rank of [key] inside its section, ahead of the editable-first ordering the platforms
     * apply: pinned keys first in [pinnedKeys] order, everything else after them.
     */
    fun pinRank(key: String): Int {
        val index = pinnedKeys.indexOf(key)
        return if (index >= 0) index else pinnedKeys.size
    }

    /**
     * The section a capability key belongs to. A key nobody has placed yet falls into
     * [Other] rather than disappearing - [System] is a deliberate section, not the
     * fallback - and the byte-by-byte rows a not-yet-decoded block produces go last.
     */
    fun of(key: String): WheelSettingGroup {
        if (key.contains("setting_byte_") ||
            key == "inmotion_settings_payload_length" ||
            key.endsWith("_word")
        ) {
            return Raw
        }
        return when (key) {
            "pedal_hardness",
            "inmotion_ride_mode",
            "inmotion_driver_mode",
            "inmotion_pedal_sensitivity_1",
            "inmotion_pedal_sensitivity_2",
            "inmotion_pitch_zero",
            "inmotion_speeding_feedback",
            "inmotion_braking_feedback",
            "inmotion_acce_feedback",
            "inmotion_assist_balance",
            "inmotion_berm_angle",
            "inmotion_berm_angle_mode",
            "angle_trim",
            "ninebot_pedal_sensitivity",
            "ninebot_brake_assist",
            "ninebot_handle_button",
            -> Ride

            "inmotion_speed_limit",
            "inmotion_speed_warning_level_1",
            "inmotion_speed_warning_level_2",
            "inmotion_output_tiltback_threshold",
            "inmotion_output_warning_threshold_1",
            "inmotion_output_warning_threshold_2",
            "ninebot_limited_mode",
            "ninebot_limited_speed",
            "ninebot_limited_speed_first_km",
            "ninebot_alarm_1_enabled",
            "ninebot_alarm_2_enabled",
            "ninebot_alarm_3_enabled",
            "ninebot_alarm_1_speed",
            "ninebot_alarm_2_speed",
            "ninebot_alarm_3_speed",
            -> Speed

            "inmotion_auto_light_state",
            "inmotion_auto_light_low_thr",
            "inmotion_auto_light_high_thr",
            "inmotion_light_brightness",
            "inmotion_low_beam_brightness",
            "inmotion_high_beam_brightness",
            "inmotion_light_effect_mode",
            "inmotion_high_beam_auto_switch_speed",
            "inmotion_auto_low_high_beam_switch_speed_thr",
            "inmotion_auto_brightness",
            "inmotion_drl_state",
            "inmotion_logo_light_brightness",
            "inmotion_logo_light_status",
            "inmotion_tail_light_mode",
            "inmotion_turn_light_mode",
            "inmotion_turn_signal_light",
            "inmotion_turn_light_state",
            "ninebot_headlight",
            "ninebot_drl",
            "ninebot_tail_light",
            "ninebot_led_mode",
            "ninebot_led_color_1",
            "ninebot_led_color_2",
            "ninebot_led_color_3",
            "ninebot_led_color_4",
            -> Light

            "inmotion_audio_switch",
            "inmotion_voice_volume",
            "inmotion_active_sound",
            "inmotion_active_sound_sensitivity",
            "ninebot_speaker_volume",
            -> Sound

            "inmotion_charge_cut_off_percent",
            "inmotion_max_charge_current_ac220",
            "inmotion_max_charge_current_ac110",
            "inmotion_low_battery_ride",
            "inmotion_dual_battery_mode",
            "inmotion_range_estimate",
            -> Battery

            "inmotion_lift_up_detection",
            "inmotion_load_detect",
            "inmotion_no_load_detect",
            "inmotion_lock_mode",
            "inmotion_transport_mode",
            "inmotion_auto_lock",
            "inmotion_tpms_low_alarm_threshold",
            "inmotion_shield_tps_error",
            "ninebot_lock_mode",
            -> Safety

            "inmotion_standby_time",
            "inmotion_auto_close_screen",
            "inmotion_usb_power_switch",
            "inmotion_touch_key",
            "inmotion_fan_status",
            "inmotion_fan_mute_mode",
            "inmotion_tbox_low_battery_wakeup",
            "inmotion_show_tbox_info",
            -> System

            else -> Other
        }
    }
}
