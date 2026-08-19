package pw.vasilevskiy.loeuc.shared.api

/** Physical unit a [DeviceMetric] is reported in. */
enum class MetricUnit {
    KmH, Percent, Volt, Ampere, Watt, Celsius, Degree, Km, Second, Count, AmpHour, WattHour, Bar,

    /** A binary state represented as 1 or 0. Missing means unreported, not zero. */
    Flag,
}

/**
 * A telemetry quantity, named the same way whatever brand reported it.
 *
 * The catalogue grows as brand mappings are written; every value here is one some wheel or
 * charger actually reports.
 */
enum class DeviceMetric(val unit: MetricUnit) {
    SpeedKmh(MetricUnit.KmH),
    PwmPercent(MetricUnit.Percent),

    PackVoltageV(MetricUnit.Volt),
    CellVoltageAvgV(MetricUnit.Volt),
    CellVoltageDeltaV(MetricUnit.Volt),

    /** Current in and out of the pack. What a rider means by "current". */
    BatteryCurrentA(MetricUnit.Ampere),

    /** Controller output current. Second-choice source for wheels that report no pack current. */
    OutputCurrentA(MetricUnit.Ampere),
    PhaseCurrentA(MetricUnit.Ampere),

    PowerW(MetricUnit.Watt),

    /** Power at the motor rather than at the pack. */
    MotorPowerW(MetricUnit.Watt),

    /** Voltage times phase current scaled by duty. Computed, never on the wire. */
    ApparentPowerVa(MetricUnit.Watt),

    BatteryPercent(MetricUnit.Percent),

    /** The wheel's general controller probe. Not the same sensor as [MosTemperatureC]. */
    ControllerTemperatureC(MetricUnit.Celsius),
    MosTemperatureC(MetricUnit.Celsius),
    MotorTemperatureC(MetricUnit.Celsius),
    BoardTemperatureC(MetricUnit.Celsius),
    BatteryTemperatureMinC(MetricUnit.Celsius),
    BatteryTemperatureMaxC(MetricUnit.Celsius),

    /** Live lean along the wheel. Not the configured fall-protection threshold. */
    PitchDeg(MetricUnit.Degree),

    /** Live lean across the wheel. */
    RollDeg(MetricUnit.Degree),

    /** Configured fall-protection angle; unlike [PitchDeg], this is not a live reading. */
    FallProtectionAngleDeg(MetricUnit.Degree),

    /** Speed at which the wheel starts tilting the rider back. A setting, not a reading. */
    DangerSpeedKmh(MetricUnit.KmH),

    TirePressureBar(MetricUnit.Bar),

    TripDistanceKm(MetricUnit.Km),
    TotalDistanceKm(MetricUnit.Km),

    // Charger-side quantities. A charger reports no pack voltage, power or controller
    // temperature of its own, so these stand in for them - see MetricIdSources.
    ChargeVoltageV(MetricUnit.Volt),
    ChargeCurrentA(MetricUnit.Ampere),
    ChargePowerW(MetricUnit.Watt),
    ChargerTemperatureC(MetricUnit.Celsius),

    /** The voltage the charger is aiming for. Only SKAT frames carry one. */
    ChargeTargetVoltageV(MetricUnit.Volt),
    ChargedAmpHours(MetricUnit.AmpHour),
    ChargedWattHours(MetricUnit.WattHour),

    /**
     * 1 while energy is flowing, 0 while it is not, absent while the charger has not said.
     * The third state is load-bearing: a confident "not charging" before the first frame is
     * something a rule can act on, and it would be wrong.
     */
    Charging(MetricUnit.Flag),
}
