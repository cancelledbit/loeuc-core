package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Which way energy is moving: `+1` while the pack drives the motor, `-1` while the motor returns
 * energy to the pack.
 *
 * ## Why a rule and not a sign test
 *
 * Wheels put a signed current word on the wire, but the sign is taken against the *direction of
 * travel*, not against the direction of power flow - and the speed word carries a direction of its
 * own. On capture `385CFBC9BF07/20260727_084903`, twenty thousand frames of a real Begode ride,
 * every moving sample reads a negative speed, so a bare `current < 0` test would have called the
 * entire ride regeneration.
 *
 * What separates driving from braking is whether the two signs *agree*. On that capture, binning
 * the moving samples by acceleration:
 *
 *     acceleration            samples agreeing
 *     over +3 km/h/s          97.0 %
 *     +1.5..+3                95.3 %
 *     -0.5..+0.5 (cruise)     84.3 %
 *     -1.5..-0.5              74.0 %
 *     under -2 km/h/s         46.4 %
 *
 * Monotone across the whole range, which a coincidence would not be.
 *
 * ## What it is worth
 *
 * Integrating that ride with the sign gives 190.8 Wh; taking the magnitude, as this code used to,
 * gives 280.6 Wh - overstated by 47 %, because 44.9 Wh came back through braking and got counted
 * as consumption. The error is not a scale factor: it grows with how much the rider brakes, so it
 * cannot be calibrated away downstream.
 *
 * ## Polarity is per brand, and is not transferable
 *
 * Which way round the agreement runs is a property of the firmware's axis convention, not of
 * physics, so it has to be established separately for every brand - see [TorqueSignConvention].
 * Assuming otherwise has already cost one wrong release: LeaperKim's `@16` is a q-axis current
 * just like Begode's `@10`, that likeness was taken for agreement, and on the first real ride
 * every sign came out backwards.
 *
 * ## At a standstill
 *
 * Zero speed has no direction to agree with, and a balancing wheel is still drawing from the pack,
 * so a stationary sample counts as driving.
 */
internal fun powerFlowSign(
    currentRaw: Int,
    speedRaw: Int,
    convention: TorqueSignConvention,
): Double {
    if (speedRaw == 0 || currentRaw == 0) return 1.0
    val agree = (currentRaw < 0) == (speedRaw < 0)
    val driving = when (convention) {
        TorqueSignConvention.AgreementIsDriving -> agree
        TorqueSignConvention.AgreementIsBraking -> !agree
    }
    return if (driving) 1.0 else -1.0
}

/**
 * How this brand's current sign relates to its speed sign.
 *
 * A type rather than a boolean flag, deliberately: this is a firmware convention, established
 * per brand by its own capture, and the caller has to name it out loud instead of inheriting a
 * default from the neighbour.
 */
internal enum class TorqueSignConvention {
    /**
     * Signs agree means the pack is pulling. Established on capture
     * `385CFBC9BF07/20260727_084903`: the share of agreeing samples falls monotonically from 97 %
     * under hard acceleration to 46 % under hard braking.
     */
    AgreementIsDriving,

    /**
     * Signs agree means the motor is giving back. Established on ride `LK21571` of 2026-08-20,
     * 4.33 km and 1688 moving samples: mean power under hard acceleration is -1801 W against
     * +130 W under hard braking, and the correlation with acceleration is -0.620. Under this
     * convention the integral comes to +108.5 Wh, which agrees with the pack falling from 129.8 V
     * to 127.1 V; the opposite convention gave -108.5 Wh, i.e. a ride that generates energy.
     */
    AgreementIsBraking,
}
