package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.abs
import kotlin.math.max

private data class LeaperKimFieldWeakeningCalibration(
    val hardwareCode: String,
    val firmwareVersion: Int,
    val mode: Mode,
) {
    sealed interface Mode {
        data class VoltageThreshold(
            val normalBase: Double,
            val normalThreshold: Double,
            val highSpeedBase: Double,
            val highSpeedThreshold: Double,
            val slope: Double,
        ) : Mode

        data class Fixed(
            val normalValue: Double,
            val highSpeedValue: Double,
        ) : Mode
    }
}

private val KnownCalibrations = listOf(
    LeaperKimFieldWeakeningCalibration(
        // 006.0.08: normal/high-speed branches use fixed coefficients.
        hardwareCode = "0060",
        firmwareVersion = 6008,
        mode = LeaperKimFieldWeakeningCalibration.Mode.Fixed(-300.0, -600.0),
    ),
    LeaperKimFieldWeakeningCalibration(
        // 006.0.09: thresholds 2800/3820, correction denominator 3400.
        hardwareCode = "0060",
        firmwareVersion = 6009,
        mode = LeaperKimFieldWeakeningCalibration.Mode.VoltageThreshold(
            normalBase = -450.0,
            normalThreshold = 2_800.0,
            highSpeedBase = -800.0,
            highSpeedThreshold = 3_820.0,
            slope = 4.0,
        ),
    ),
    LeaperKimFieldWeakeningCalibration(
        // 006.0.10: thresholds 2930/3920, correction denominator 2400.
        hardwareCode = "0060",
        firmwareVersion = 6010,
        mode = LeaperKimFieldWeakeningCalibration.Mode.VoltageThreshold(
            normalBase = -400.0,
            normalThreshold = 2_930.0,
            highSpeedBase = -700.0,
            highSpeedThreshold = 3_920.0,
            slope = 4.0,
        ),
    ),
    LeaperKimFieldWeakeningCalibration(
        // 007.0.03: normal/high-speed branches use fixed coefficients.
        hardwareCode = "0070",
        firmwareVersion = 7003,
        mode = LeaperKimFieldWeakeningCalibration.Mode.Fixed(-150.0, -350.0),
    ),
    LeaperKimFieldWeakeningCalibration(
        hardwareCode = "0080",
        firmwareVersion = 8005,
        mode = newVoltageCalibration(),
    ),
    LeaperKimFieldWeakeningCalibration(
        hardwareCode = "0090",
        firmwareVersion = 9004,
        mode = newVoltageCalibration(),
    ),
)

private fun newVoltageCalibration(): LeaperKimFieldWeakeningCalibration.Mode {
    return LeaperKimFieldWeakeningCalibration.Mode.VoltageThreshold(
        normalBase = -500.0,
        normalThreshold = 3_480.0,
        highSpeedBase = -750.0,
        highSpeedThreshold = 4_100.0,
        slope = 4.0,
    )
}

fun calculateLeaperKimFieldWeakening(
    packVoltage: Double,
    highSpeedMode: Boolean,
    hardwareCode: String?,
    firmwareVersion: Int?,
): Double? {
    val calibration = KnownCalibrations.firstOrNull { profile ->
        profile.hardwareCode == hardwareCode && profile.firmwareVersion == firmwareVersion
    } ?: return null
    val signedFlu = when (val mode = calibration.mode) {
        is LeaperKimFieldWeakeningCalibration.Mode.Fixed -> {
            if (highSpeedMode) mode.highSpeedValue else mode.normalValue
        }
        is LeaperKimFieldWeakeningCalibration.Mode.VoltageThreshold -> {
            if (!packVoltage.isFinite() || packVoltage <= 0.0) return null
            val sysVolPoint = packVoltage * 100.0 / 4.2
            val base = if (highSpeedMode) mode.highSpeedBase else mode.normalBase
            val threshold = if (highSpeedMode) mode.highSpeedThreshold else mode.normalThreshold
            base + max(sysVolPoint - threshold, 0.0) * mode.slope
        }
    }
    return abs(signedFlu) / 10.0
}
