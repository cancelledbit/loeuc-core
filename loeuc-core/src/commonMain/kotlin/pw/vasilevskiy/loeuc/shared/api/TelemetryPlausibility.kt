package pw.vasilevskiy.loeuc.shared.api

/**
 * Decides whether a decoded frame is worth publishing at all.
 *
 * ## Why the rule is here and not in a shell
 *
 * Both shells already needed it and only one had it: iOS carries `WheelTelemetryPlausibility`,
 * Android carried nothing, so a garbage frame reached the dashboard, the alert engine and - the
 * part that cannot be taken back - the ride database. Writing it a second time by hand would have
 * made a third set of limits to keep in step, which is exactly how the two shells drifted apart
 * everywhere else. The rule is byte-level protocol semantics: it says which numbers a wheel can
 * physically report, and that belongs next to the engines that produce them.
 *
 * The signature is plain nullable numbers rather than any one shell's telemetry type on purpose.
 * Android's `TelemetrySnapshot` and iOS's `WheelTelemetry` are different types with different ways
 * of spelling "no reading" (`null` against `NaN`), and neither is in this library; a rule that
 * insisted on one of them could only ever serve one shell.
 *
 * ## Why it is needed
 *
 * Begode and KingSong assemble telemetry out of 20-byte notifications **with no CRC**. A
 * truncated, interleaved or misaligned notification therefore decodes into a perfectly
 * well-formed snapshot, and the only thing that gives it away is that the numbers in it are
 * impossible. One false "190 km/h" is one spurious alert in the rider's ear and one permanent row
 * in the ride history, where it also becomes the ride's maximum speed forever.
 *
 * The limits are deliberately wide. They catch a decode that has come apart, not a reading that
 * looks surprising: a real wheel doing 130 km/h downhill must still get through.
 */
object TelemetryPlausibility {

    /**
     * Wheel frame: speed, PWM and pack voltage are the three readings every wheel protocol in this
     * library carries, so all three have to be present and sane. Missing ones fail the check -
     * their absence is what a decode that came apart looks like.
     *
     * A reading this library never fills in is a different matter: [batteryPercent],
     * [tripDistanceMeters] and [totalDistanceMeters] are optional by protocol, so `null` passes
     * and only an out-of-range value fails. NaN counts as absent for the same reason - it is how
     * the Swift side spells "no sensor", not "the sensor lied".
     *
     * @param requiresPwm false for the wires that carry no duty cycle at all. The InMotion legacy
     * fast-info frame has no such field and the Solowheel Xtreme record is three ASCII numbers;
     * demanding a PWM of them would drop every frame they ever send.
     */
    fun isPlausibleWheelFrame(
        speedKmh: Double?,
        pwmPercent: Double?,
        packVoltage: Double?,
        batteryPercent: Double? = null,
        tripDistanceMeters: Double? = null,
        totalDistanceMeters: Double? = null,
        requiresPwm: Boolean = true,
    ): Boolean {
        if (!required(speedKmh, SpeedRange)) return false
        if (!required(packVoltage, VoltageRange)) return false
        if (requiresPwm) {
            if (!required(pwmPercent, PwmRange)) return false
        } else if (!optional(pwmPercent, PwmRange)) {
            return false
        }
        return optional(batteryPercent, BatteryPercentRange) &&
            optional(tripDistanceMeters, DistanceMetersRange) &&
            optional(totalDistanceMeters, DistanceMetersRange)
    }

    /**
     * Charger frame: the wheel rule rejects every one of these, because a charger has neither a
     * pack of its own nor a wheel controller and so reports no speed, no PWM and no pack voltage.
     * That is not hypothetical - on iOS the wheel rule was applied to chargers and silently ate
     * `HWCDQ_40FE0409`'s entire stream.
     *
     * Both charger protocols frame with a length and a checksum, so garbage barely reaches here
     * and the check is really for something else: a frame must carry at least one reading.
     * Publishing a snapshot of nothing is worse than publishing none, because the dashboard shows
     * dashes while the link indicator reports a healthy stream.
     */
    fun isPlausibleChargerFrame(
        chargeVoltage: Double? = null,
        chargeCurrent: Double? = null,
        chargePower: Double? = null,
        chargerTemperatureCelsius: Double? = null,
        chargeTargetVoltage: Double? = null,
        chargedAmpHours: Double? = null,
        chargedWattHours: Double? = null,
    ): Boolean {
        val anyReading = present(chargeVoltage) || present(chargeCurrent) || present(chargePower) ||
            present(chargerTemperatureCelsius) || present(chargeTargetVoltage) ||
            present(chargedAmpHours) || present(chargedWattHours)
        if (!anyReading) return false
        return optional(chargeVoltage, ChargeVoltageRange) &&
            optional(chargeTargetVoltage, ChargeVoltageRange) &&
            optional(chargeCurrent, ChargeCurrentRange) &&
            optional(chargerTemperatureCelsius, ChargerTemperatureRange) &&
            optional(chargedAmpHours, ChargedAmpHoursRange) &&
            optional(chargedWattHours, ChargedWattHoursRange)
    }

    /**
     * Whether a protocol's wire carries a duty cycle. Kept beside the limits so a new protocol
     * answers the question once, here, instead of in each shell's gate.
     */
    fun carriesPwm(protocol: DeviceProtocol?): Boolean = when (protocol) {
        WheelProtocol.InmotionLegacy, WheelProtocol.SolowheelXtreme -> false
        else -> true
    }

    // Signed: engines keep the direction of travel (a wheel rolling back reports a negative
    // speed), and a 0..200 range dropped every such frame on Android while iOS allowed -200..200.
    private val SpeedRange = -200.0..200.0
    private val PwmRange = 0.0..200.0
    private val VoltageRange = 20.0..300.0
    private val BatteryPercentRange = 0.0..100.0
    private val DistanceMetersRange = 0.0..100_000_000.0
    private val ChargeVoltageRange = 0.0..600.0
    private val ChargeCurrentRange = -100.0..100.0
    private val ChargerTemperatureRange = -50.0..200.0
    private val ChargedAmpHoursRange = 0.0..10_000.0
    private val ChargedWattHoursRange = 0.0..1_000_000.0

    private fun present(value: Double?): Boolean = value != null && !value.isNaN()

    private fun required(value: Double?, range: ClosedRange<Double>): Boolean =
        present(value) && value!! in range

    /** An absent reading passes: NaN and null mean "no sensor", not "the sensor lied". */
    private fun optional(value: Double?, range: ClosedRange<Double>): Boolean =
        !present(value) || value!! in range
}
