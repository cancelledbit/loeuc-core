package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.math.abs

data class LeaperKimTelemetry(
    val speedKmh: Double,
    val pwmPercent: Double,
    val voltage: Double,
    val phaseCurrent: Double,
    val outputCurrent: Double,
    val power: Double,
    val temperature: Double,
    val mosTemperature: Double,
    val motorTemperature: Double,
    val batteryPercent: Double,
    val tripDistanceMeters: Double,
    val totalDistanceMeters: Double,
    val batteryCurrent: Double,
    val leftBatteryCurrent: Double,
    val rightBatteryCurrent: Double,
    val lateralAngle: Double,
    val carPose: Double,
    val batteryTemperatureMin: Double,
    val batteryTemperatureMax: Double,
    val dangerSpeedKmh: Double,
    val stopSpeedKmh: Double,
    val fallProtectionAngle: Double,
    val rideMode: Double,
    val shutdownTimeSeconds: Double,
    val chargeMode: Double,
    val batteryTempMode: Double,
    val lockState: Double,
    val hardwareCode: String?,
    val firmwareVersion: Double,
    val highSpeedMode: Boolean,
    val fieldWeakeningPercent: Double,
)

data class LeaperKimBmsCell(
    val index: Int,
    val voltage: Double,
)

data class LeaperKimBmsTemperature(
    val index: Int,
    val celsius: Double,
)

data class LeaperKimBmsBattery(
    val index: Int,
    val expectedCellCount: Int,
    val parallelStrings: Int,
    val cells: List<LeaperKimBmsCell>,
    val currentAmps: Double,
    val temperatures: List<LeaperKimBmsTemperature>,
    val maxChargeVoltage: Double,
)

data class LeaperKimBmsSnapshot(
    val profileId: String,
    val modelName: String,
    val batteries: List<LeaperKimBmsBattery>,
)

data class LeaperKimSettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    val rawValue: Int,
    val sourceOffset: Int,
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val kind: String,
    val baseValue: Int? = null,
    /**
     * False when the current wheel firmware doesn't report this field this cycle (raw byte
     * reads the 0x80 "not reported" sentinel). [rawValue] is meaningless when false - mirrors
     * Android's WheelSettingCapability.isSupported semantics so both platforms expose the same
     * 16-entry capability set, just with different fields flagged unsupported per wheel.
     */
    val isSupported: Boolean = true,
)

class LeaperKimProtocolEngine {
    private var buffer = byteArrayOf()
    private var batteryPercent = Double.NaN

    /**
     * True once page 2 has reported a real percentage at offset 50. Only then may the
     * voltage-curve estimate be suppressed - see the battery block in [toTelemetry].
     */
    private var hasReportedBatteryPercent = false
    private var lastVoltage = Double.NaN
    private var leftBatteryCurrent = Double.NaN
    private var rightBatteryCurrent = Double.NaN
    private var lateralAngle = Double.NaN
    private var pedalAngleTrim = Double.NaN
    private var mosTemperature = Double.NaN
    private var fallProtectionAngle = Double.NaN
    private var settings = emptyList<LeaperKimSettingCapability>()
    private var hardwareCode: String? = null
    private val batteryCells = List(2) { mutableMapOf<Int, Double>() }
    private val batteryTemperatures = List(2) { mutableMapOf<Int, Double>() }
    private val reportedCellCounts = arrayOfNulls<Int>(2)
    private val signedBatteryCurrents = DoubleArray(2) { Double.NaN }
    private var maxChargeVoltage = Double.NaN
    private var lockState = Double.NaN
    private var highSpeedMode = false
    private var firmwareVersion = Double.NaN

    fun reset() {
        buffer = byteArrayOf()
        batteryPercent = Double.NaN
        hasReportedBatteryPercent = false
        lastVoltage = Double.NaN
        leftBatteryCurrent = Double.NaN
        rightBatteryCurrent = Double.NaN
        lateralAngle = Double.NaN
        pedalAngleTrim = Double.NaN
        mosTemperature = Double.NaN
        fallProtectionAngle = Double.NaN
        settings = emptyList()
        hardwareCode = null
        batteryCells.forEach { it.clear() }
        batteryTemperatures.forEach { it.clear() }
        reportedCellCounts.fill(null)
        signedBatteryCurrents.fill(Double.NaN)
        maxChargeVoltage = Double.NaN
        lockState = Double.NaN
        highSpeedMode = false
        firmwareVersion = Double.NaN
    }

    /**
     * Existing UI-facing capability list - unsupported fields (isSupported=false) are
     * dropped, preserving the behavior every current caller (iOS settings screen) already
     * relies on.
     */
    fun settingsCapabilities(): List<LeaperKimSettingCapability> {
        return allSettingsCapabilities().filter { it.isSupported }
    }

    /**
     * Full capability list including fields the current wheel doesn't report this cycle
     * (isSupported=false, rawValue meaningless) - matches Android's WheelSettingCapability
     * list size/shape. Intended for callers that need to know the complete set of settings
     * a wheel model *could* expose (e.g. the AlertCommand action picker), as opposed to
     * [settingsCapabilities] which is for display of currently-known values only.
     */
    fun allSettingsCapabilities(): List<LeaperKimSettingCapability> {
        return settings.map { capability ->
            // angle_trim is a setting mirrored in the page-0/4 extension, not live roll. It is
            // supported only once a frame has actually supplied that separate trim value.
            // Until then it stays
            // isSupported = false, which is how every platform already renders an absent field.
            if (capability.key == "angle_trim" && pedalAngleTrim.isFinite()) {
                val raw = (pedalAngleTrim * 10.0).toInt().coerceIn(-80, 80)
                capability.copy(rawValue = raw, isSupported = true)
            } else {
                capability
            }
        }
    }

    fun bmsSnapshot(): LeaperKimBmsSnapshot {
        val profile = hardwareCode?.let(LeaperKimBmsProfiles::get) ?: LeaperKimBmsProfile(
            profileId = "leaperkim_generic",
            modelName = "Generic LeaperKim",
            batteryCount = 2,
            cellCount = reportedCellCounts.filterNotNull().maxOrNull() ?: guessSCountFromVoltage(lastVoltage),
            totalParallelStrings = 2,
        )
        return LeaperKimBmsSnapshot(
            profileId = profile.profileId,
            modelName = profile.modelName,
            batteries = List(profile.batteryCount) { batteryIndex ->
                LeaperKimBmsBattery(
                    index = batteryIndex + 1,
                    expectedCellCount = reportedCellCounts[batteryIndex] ?: profile.cellCount,
                    parallelStrings = profile.parallelStringsPerBattery,
                    cells = batteryCells[batteryIndex]
                        .map { (index, voltage) -> LeaperKimBmsCell(index, voltage) }
                        .sortedBy(LeaperKimBmsCell::index),
                    currentAmps = signedBatteryCurrents[batteryIndex],
                    temperatures = batteryTemperatures[batteryIndex]
                        .map { (index, celsius) -> LeaperKimBmsTemperature(index, celsius) }
                        .sortedBy(LeaperKimBmsTemperature::index),
                    maxChargeVoltage = maxChargeVoltage,
                )
            },
        )
    }

    @ExperimentalWriteApi
    fun buildSettingCommand(key: String, value: Int): ByteArray? {
        // The derived list, not the raw parse: a derived capability such as angle_trim is only
        // marked supported there, and refusing to write it would be exactly backwards - the write
        // sets a new trim, it does not depend on the byte the wheel reports.
        val capability = allSettingsCapabilities().firstOrNull { it.key == key } ?: return null
        if (!capability.isSupported) {
            return null
        }
        val commandId = capability.commandId ?: return null
        if (value !in capability.minValue..capability.maxValue) {
            return null
        }
        if (commandId == 16) {
            val legacy = byteArrayOf(
                'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
                0x10, 0x01, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), value.toByte(),
            ).withCrc32()
            val new = byteArrayOf(
                'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
                0x10, 0x01, 0x00, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), value.toByte(),
            ).withCrc32()
            return legacy + new
        }
        val valueIndex = commandId - 5
        if (valueIndex < 7) {
            return null
        }
        val payload = ByteArray(valueIndex + 1) { 0x80.toByte() }
        payload[0] = 'L'.code.toByte()
        payload[1] = 'd'.code.toByte()
        payload[2] = 'A'.code.toByte()
        payload[3] = 'p'.code.toByte()
        payload[4] = commandId.toByte()
        payload[5] = 0x01
        payload[6] = 0x02
        payload[valueIndex] = value.toByte()
        return payload.withCrc32()
    }

    @ExperimentalWriteApi
    fun buildFixedShutdownTimerCommand(): ByteArray {
        return byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x16, 0x80.toByte(), 0x00,
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x01, 0x80.toByte(),
        ).withCrc32()
    }

    @ExperimentalWriteApi
    fun buildLightCommand(enabled: Boolean): ByteArray {
        val value = if (enabled) 0x01 else 0x00
        val oldCommand = byteArrayOf(
            'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x0D, 0x01, 0x80.toByte(), 0x80.toByte(), value.toByte(),
        ).withCrc32()
        val newCommand = byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x0D, 0x01, 0x00, 0x80.toByte(), value.toByte(),
        ).withCrc32()
        return oldCommand + newCommand
    }

    fun consume(chunk: ByteArray): LeaperKimTelemetry? {
        buffer += chunk
        var latest: LeaperKimTelemetry? = null

        while (true) {
            val markerIndex = buffer.indexOfMarker()
            if (markerIndex < 0) {
                buffer = buffer.takeLast(2).toByteArray()
                return latest
            }
            if (markerIndex > 0) {
                buffer = buffer.copyOfRange(markerIndex, buffer.size)
            }
            if (buffer.size < 4) {
                return latest
            }

            val frameLength = buffer.u8(3) + 4
            if (buffer.size < frameLength) {
                return latest
            }
            val frame = buffer.copyOfRange(0, frameLength)
            buffer = buffer.copyOfRange(frameLength, buffer.size)

            // Legacy Sherman/ShermanMax frames are 36 bytes with no CRC32 trailer. Nothing in the
            // newer format is anywhere near that short - Patton's page 3, the shortest CRC32 frame
            // observed, is 51 bytes - so the declared length alone separates the two formats.
            if (frameLength in LegacyFrameLengthRange) {
                latest = frame.toTelemetry(isLegacy = true)
                continue
            }
            if (!frame.hasValidCrc32()) {
                continue
            }
            // Decode the payload only. Frame length is per-page and per-model: Lynx-S/Oryx send a
            // 87-byte page 2, Patton (hardware 0040, no Smart BMS) only 54, so offsets that are
            // payload on one model land in the CRC32 trailer on another. Every `size` guard and
            // `*OrNull` accessor below bounds against the array it is given, so handing them the
            // trailer-free payload is what makes "field absent on this model" read as absent
            // instead of as four bytes of checksum noise.
            latest = frame.copyOfRange(0, frameLength - CRC32_SIZE).toTelemetry(isLegacy = false)
        }
    }

    private fun ByteArray.toTelemetry(isLegacy: Boolean): LeaperKimTelemetry? {
        // Legacy frames are exactly 36 bytes; CRC32 payloads start at 38.
        val minimumSize = if (isLegacy) LegacyFrameSize else 38
        if (size < minimumSize) {
            return null
        }
        val page = getOrNull(46)?.toInt()?.and(0xFF)
        readHardwareCode()?.let { hardwareCode = it }
        updateBmsState(page)

        val voltageRaw = uint16Be(4)
        val voltage = voltageRaw / 100.0
        lastVoltage = voltage

        // Two separate ideas, and they used to be conflated into one `batteryPercent.isNaN()`
        // guard: "a voltage estimate must never overwrite a percentage the BMS reported itself"
        // (still true, now expressed by [hasReportedBatteryPercent]) and "estimate only once"
        // (never intended). The legacy 36-byte Sherman Max frame has no byte 46, so `page` reads
        // null and every frame took the else branch - the estimate was computed from the first
        // frame the engine ever saw and then frozen for the life of the engine.
        if (page == 2) {
            val reportedPercent = u8OrNull(50)
            if (reportedPercent != null && reportedPercent != 0x80 && reportedPercent <= 100) {
                batteryPercent = reportedPercent.toDouble()
                hasReportedBatteryPercent = true
            } else if (voltageRaw > 0) {
                calculateLeaperKimBatteryPercent(voltageRaw, hardwareCode)?.let { batteryPercent = it }
            }
        } else if (!hasReportedBatteryPercent && voltageRaw > 0) {
            calculateLeaperKimBatteryPercent(voltageRaw, hardwareCode)?.let { batteryPercent = it }
        }

        if (page == 0 || page == 4) {
            // Bounds are per field, not one guard for the block: Patton's 69-byte page-0 payload
            // carries the MOS temperature and lateral angle but stops before the battery currents,
            // so a single `size` check would either drop all four or read the currents past the end.
            int16BeOrNull(59)
                ?.div(100.0)
                ?.takeIf { it in -40.0..200.0 }
                ?.let { mosTemperature = it }
            int16BeOrNull(57)?.let { lateralAngle = it / 100.0 }
            int16BeOrNull(67)?.let { pedalAngleTrim = it / 100.0 }
            val left = int16BeOrNull(69)
            val right = int16BeOrNull(71)
            if (left != null && right != null) {
                leftBatteryCurrent = abs(left) / 100.0
                rightBatteryCurrent = abs(right) / 100.0
                signedBatteryCurrents[0] = left / 100.0
                signedBatteryCurrents[1] = right / 100.0
            }
        }
        if (page == 2 && size > 47) {
            fallProtectionAngle = signedByte(47).toDouble()
        }
        if (page == 8) {
            settings = parseSettings()
            val adjustment = controlByte(64)
            if (adjustment != null) {
                maxChargeVoltage = (controlByte(65) ?: 145) + adjustment / 10.0
            }
        }
        if (page == 5) {
            u8OrNull(51)?.let { lockState = it.toDouble() }
        }
        if (page == 8) {
            highSpeedMode = (controlByte(61) ?: 0) > 0
        }
        readFirmwareVersion()?.let { firmwareVersion = it.toDouble() }

        val phaseCurrent = abs(int16Be(16)) / 10.0
        val outputRaw = uint16Be(34)
        val outputCurrent = phaseCurrent * outputRaw / 10_000.0
        return LeaperKimTelemetry(
            speedKmh = abs(int16Be(6)) / 10.0,
            pwmPercent = outputRaw / 100.0,
            voltage = voltage,
            phaseCurrent = phaseCurrent,
            outputCurrent = outputCurrent,
            power = outputCurrent * voltage,
            temperature = int16Be(18) / 100.0,
            mosTemperature = mosTemperature,
            // The wheel does not report motor temperature at all. This used to read
            // `int16Be(38) / 10`, which is zero at a standstill and a plausible-looking number
            // under load. Disassembling firmware `0090.04` showed that the frame builder at
            // `FUN_0801ABE8` takes those bytes from `0x2000058A` - the `AI` field of the
            // diagnostic co-stream, outside the temperature block at `0x20005F0C`. Neither the
            // measured motor probe (`0x20005F18`) nor the modelled coil temperature
            // (`0x20005F1C`) is written into a `DC 5A 5C` frame: this firmware puts two
            // temperatures on the wire, not four.
            motorTemperature = Double.NaN,
            batteryPercent = batteryPercent,
            tripDistanceMeters = distance(8),
            totalDistanceMeters = distance(12),
            batteryCurrent = if (leftBatteryCurrent.isFinite() && rightBatteryCurrent.isFinite()) {
                leftBatteryCurrent + rightBatteryCurrent
            } else {
                Double.NaN
            },
            leftBatteryCurrent = leftBatteryCurrent,
            rightBatteryCurrent = rightBatteryCurrent,
            lateralAngle = lateralAngle,
            carPose = int16Be(32).toDouble(),
            batteryTemperatureMin = currentBatteryTemperatures.minOrNull() ?: Double.NaN,
            batteryTemperatureMax = currentBatteryTemperatures.maxOrNull() ?: Double.NaN,
            dangerSpeedKmh = uint16Be(24) / 10.0,
            stopSpeedKmh = uint16Be(26) / 10.0,
            fallProtectionAngle = fallProtectionAngle,
            rideMode = u8OrNull(31)?.toDouble() ?: Double.NaN,
            shutdownTimeSeconds = uint16Be(20).toDouble(),
            chargeMode = u8OrNull(23)?.toDouble() ?: Double.NaN,
            batteryTempMode = uint16BeOrNull(36)?.toDouble() ?: Double.NaN,
            lockState = lockState,
            hardwareCode = hardwareCode,
            firmwareVersion = firmwareVersion,
            highSpeedMode = highSpeedMode,
            fieldWeakeningPercent = calculateLeaperKimFieldWeakening(
                packVoltage = voltage,
                highSpeedMode = highSpeedMode,
                hardwareCode = hardwareCode,
                firmwareVersion = firmwareVersion.takeIf { it.isFinite() }?.toInt(),
            ) ?: Double.NaN,
        )
    }

    private fun ByteArray.parseSettings(): List<LeaperKimSettingCapability> {
        val baseVol = u8OrNull(65)
        return SettingDefinitions.map { definition ->
            // 0x80 is the wire "field not reported this cycle" sentinel. Every defined field
            // is always exposed (matching Android's WheelSettingCapability list) - a missing
            // reading is surfaced as isSupported=false rather than dropping the entry, so the
            // UI/AlertCommand picker can show a consistent settings list per wheel model.
            // A derived setting has no byte to read here at all; [allSettingsCapabilities] fills
            // it in from live telemetry, and it stays unsupported until that telemetry arrives.
            val raw = if (definition.isDerived) {
                null
            } else {
                u8OrNull(definition.offset)?.takeUnless { it == 0x80 }
            }
            LeaperKimSettingCapability(
                key = definition.key,
                titleEn = definition.titleEn,
                titleRu = definition.titleRu,
                rawValue = when {
                    raw == null -> 0
                    definition.key == "voltage_correction" && raw >= 0x80 -> raw - 0x100
                    else -> raw
                },
                sourceOffset = definition.offset,
                commandId = definition.commandId,
                minValue = definition.minValue,
                maxValue = definition.maxValue,
                kind = definition.kind,
                baseValue = if (definition.key == "max_charge_voltage") baseVol else null,
                isSupported = raw != null,
            )
        }
    }

    /**
     * The newest reading of every battery temperature sensor the wheel has reported so far, from
     * the same per-sensor maps [bmsSnapshot] publishes. Deliberately not a running min/max: a
     * monotonic accumulator latches a transient sensor spike, and Android rebuilds the engine and
     * replays a window of frames for every snapshot, so a latched spike would sit on the
     * dashboard until it aged out of that window. [updateBmsState] already applies the
     * -40..120 C plausibility filter when it writes these maps, so a garbage reading can never
     * become the min or the max here.
     */
    private val currentBatteryTemperatures: List<Double>
        get() = batteryTemperatures.flatMap { sensors -> sensors.values }

    private fun ByteArray.updateBmsState(page: Int?) {
        val batteryIndex = when (page) {
            in 1..3 -> 0
            in 5..7 -> 1
            else -> return
        }
        val localPage = page!! - batteryIndex * 4
        val profile = hardwareCode?.let(LeaperKimBmsProfiles::get)
        if (localPage == 1) {
            reportedCellCounts[batteryIndex] = u8OrNull(52)
                ?.takeIf { it in 1..120 }
                ?: reportedCellCounts[batteryIndex]
        }
        val expectedCount = reportedCellCounts[batteryIndex]
            ?: profile?.cellCount
            ?: 36

        fun readCells(startIndex: Int, sourceOffset: Int, maxCount: Int) {
            // `size` is already the CRC-stripped payload length, so no trailer allowance here.
            val availableCount = ((size - sourceOffset) / 2).coerceAtLeast(0)
            val count = minOf(maxCount, availableCount, (expectedCount - startIndex).coerceAtLeast(0))
            repeat(count) { offset ->
                uint16BeOrNull(sourceOffset + offset * 2)
                    ?.takeIf { it in 2_500..4_300 }
                    ?.div(1_000.0)
                    ?.let { voltage ->
                        batteryCells[batteryIndex][startIndex + offset + 1] = voltage
                    }
            }
        }

        when (localPage) {
            1 -> readCells(startIndex = 0, sourceOffset = 53, maxCount = 15)
            2 -> readCells(startIndex = 15, sourceOffset = 53, maxCount = 15)
            3 -> {
                repeat(6) { index ->
                    int16BeOrNull(47 + index * 2)
                        ?.div(100.0)
                        ?.takeIf { it in -40.0..120.0 }
                        ?.let { batteryTemperatures[batteryIndex][index + 1] = it }
                }
                readCells(startIndex = 30, sourceOffset = 59, maxCount = 6)
                if (expectedCount > 36) {
                    readCells(
                        startIndex = 36,
                        sourceOffset = 71,
                        maxCount = expectedCount - 36,
                    )
                }
            }
        }
    }

    private fun ByteArray.readHardwareCode(): String? {
        val first = u8OrNull(30) ?: return null
        val second = u8OrNull(28) ?: return null
        val third = u8OrNull(29) ?: return null
        val version = (first shl 16) or (second shl 8) or third
        return version
            .takeIf { it > 0 }
            ?.toString()
            ?.padStart(6, '0')
            ?.take(4)
    }

    /**
     * Mirrors [readHardwareCode] but returns the full integer rather than the first four
     * digits. Same reading as `leaperKimFirmwareVersion()` in [LeaperKimFieldWeakening].
     */
    private fun ByteArray.readFirmwareVersion(): Int? {
        if (size <= 30) return null
        val first = u8(30)
        val second = u8(28)
        val third = u8(29)
        return (first shl 16) or (second shl 8) or third
    }

    private fun ByteArray.controlByte(offset: Int): Int? =
        u8OrNull(offset)?.takeUnless { it == 0x80 }

    private fun calculateLeaperKimBatteryPercent(voltageRaw: Int, hardwareCode: String?): Double? {
        val sCount = when (hardwareCode) {
            "0050", "0060", "0090", "5010", "5030" -> 36
            // Patton (0040) is 30S with a 126.0 V full charge. It was grouped with the 24S
            // Shermans, which capped the table at 100.80 V while the real wheel idles at 123.72 V
            // (dump loeuc_882583F5CF2B, device LK17344) - every reading above 100.80 V clamped to
            // a flat 100%.
            //
            // Known defect, tracked separately: NosfetAeroTable tops out at 123.75 V (4.125 V per
            // cell), below Patton's real 126.0 V full charge, so the top ~9% of the pack reads as
            // a flat 100%. See docs/leaperkim-lynxs-protocol.md.
            "0070", "5020", "0040" -> 30
            "0080" -> 42
            "0010", "0011" -> 24
            else -> {
                reportedCellCounts.filterNotNull().maxOrNull() ?: guessSCountFromVoltage(voltageRaw / 100.0)
            }
        }

        val table = when (sCount) {
            36 -> NosfetApexTable
            30 -> NosfetAeroTable
            42 -> NosfetOryxTable
            24 -> NosfetShermanTable
            else -> return null
        }
        if (voltageRaw >= table.last()) return 100.0
        if (voltageRaw <= table.first()) return 0.0

        val nextIndex = table.indexOfFirst { it > voltageRaw }
        if (nextIndex <= 0) return 0.0

        val prevIndex = nextIndex - 1
        val v0 = table[prevIndex].toDouble()
        val v1 = table[nextIndex].toDouble()

        val fraction = (voltageRaw - v0) / (v1 - v0)
        return prevIndex + fraction
    }

    private fun guessSCountFromVoltage(voltage: Double): Int {
        return if (voltage.isNaN() || voltage <= 0.0) 36
        else if (voltage > 155.0) 42
        else if (voltage > 130.0) 36
        else if (voltage > 100.0) 30
        else 24
    }
}

private data class LeaperKimBmsProfile(
    val profileId: String,
    val modelName: String,
    val batteryCount: Int,
    val cellCount: Int,
    val totalParallelStrings: Int,
) {
    val parallelStringsPerBattery: Int
        get() = totalParallelStrings / batteryCount
}

private val LeaperKimBmsProfiles = mapOf(
    "0050" to LeaperKimBmsProfile("leaperkim_0050", "Lynx", 2, 36, 2),
    "0060" to LeaperKimBmsProfile("leaperkim_0060", "Sherman-L", 2, 36, 2),
    "0070" to LeaperKimBmsProfile("leaperkim_0070", "Patton-S", 2, 30, 2),
    "0080" to LeaperKimBmsProfile("leaperkim_0080", "Oryx", 2, 42, 4),
    "0090" to LeaperKimBmsProfile("leaperkim_0090", "Lynx-S", 2, 36, 4),
    "5010" to LeaperKimBmsProfile("nosfet_5010", "Nosfet Apex", 2, 36, 4),
    "5020" to LeaperKimBmsProfile("nosfet_5020", "Nosfet Aero", 2, 30, 2),
    "5030" to LeaperKimBmsProfile("nosfet_5030", "Nosfet Aeon", 2, 36, 4),
)

private val NosfetApexTable = intArrayOf(
    11340, 11394, 11452, 11509, 11567, 11624, 11682, 11736, 11794, 11851,
    11909, 11966, 12024, 12056, 12092, 12128, 12164, 12200, 12236, 12272,
    12308, 12344, 12380, 12416, 12452, 12481, 12514, 12546, 12578, 12611,
    12643, 12676, 12708, 12740, 12773, 12805, 12838, 12870, 12899, 12928,
    12960, 12989, 13018, 13050, 13079, 13108, 13140, 13169, 13198, 13230,
    13255, 13280, 13306, 13334, 13360, 13385, 13414, 13439, 13464, 13493,
    13518, 13543, 13572, 13608, 13644, 13680, 13716, 13752, 13788, 13824,
    13860, 13896, 13932, 13968, 14004, 14044, 14083, 14123, 14166, 14206,
    14245, 14285, 14328, 14368, 14407, 14447, 14490, 14515, 14544, 14573,
    14598, 14627, 14656, 14681, 14710, 14738, 14764, 14792, 14821, 14850
)

private val NosfetAeroTable = intArrayOf(
    9450, 9495, 9543, 9591, 9639, 9687, 9735, 9780, 9828, 9876, 9924, 9972, 10020, 10047, 10077, 10107, 10137, 10167, 10197, 10227,
    10257, 10287, 10317, 10347, 10377, 10401, 10428, 10455, 10482, 10509, 10536, 10563, 10590, 10617, 10644, 10671, 10698, 10725, 10749, 10773,
    10800, 10824, 10848, 10875, 10899, 10923, 10950, 10974, 10998, 11025, 11046, 11067, 11088, 11112, 11133, 11154, 11178, 11199, 11220, 11244,
    11265, 11286, 11310, 11340, 11370, 11400, 11430, 11460, 11490, 11520, 11550, 11580, 11610, 11640, 11670, 11703, 11736, 11769, 11805, 11838,
    11871, 11904, 11940, 11973, 12006, 12039, 12075, 12096, 12120, 12144, 12165, 12189, 12213, 12234, 12258, 12282, 12303, 12327, 12351, 12375
)

private val NosfetOryxTable = NosfetApexTable.map { (it * 42 / 36) }.toIntArray()

private val NosfetShermanTable = intArrayOf(
    7560, 7596, 7644, 7692, 7740, 7788, 7836, 7884, 7932, 7980,
    8028, 8076, 8124, 8172, 8220, 8268, 8316, 8364, 8412, 8460,
    8508, 8556, 8604, 8652, 8700, 8748, 8796, 8844, 8892, 8940,
    8988, 9036, 9084, 9132, 9180, 9228, 9276, 9324, 9372, 9420,
    9468, 9516, 9564, 9612, 9660, 9708, 9756, 9804, 9852, 9900,
    9948, 9996, 10044, 10080
)

private data class SettingDefinition(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    val offset: Int,
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val kind: String = "slider",
    /**
     * True when the value has no byte of its own on page 8 and is derived from live telemetry
     * instead. [offset] is then only a display label, never something [parseSettings] may read:
     * `angle_trim`'s "offset 0" is the 0xDC frame marker, which reads as 220 and is not the 0x80
     * "not reported" sentinel, so reading it published 22.0 degrees of pedal trim on a level wheel.
     */
    val isDerived: Boolean = false,
)

private val SettingDefinitions = listOf(
    SettingDefinition("pedal_hardness", "Pedal hardness / ride mode", "Жесткость педалей / режим езды", 50, 15, 0, 100),
    SettingDefinition("stop_speed", "Tiltback speed", "Скорость tiltback", 52, 17, 10, 120),
    SettingDefinition("stop_power", "PWM threshold", "Порог PWM", 53, 18, 30, 100),
    SettingDefinition("speed_alarm", "Alarm speed", "Скорость тревоги", 54, 19, 10, 120),
    SettingDefinition("screen_backlight", "Screen backlight", "Подсветка экрана", 55, 20, 0, 100),
    // commandId is deliberately null (not "we haven't found the write command yet"):
    // LoEUC never exposes gyroscope calibration as something an app control (settings
    // slider, AlertCommand, ...) can trigger remotely, on any wheel - the real procedure
    // requires the wheel to be physically leveled (e.g. hung level) first, which the app
    // has no way to verify. buildSettingCommand() returns null for it unconditionally via
    // the `commandId ?: return null` check below. Do not "fix" this by wiring up a real
    // commandId even if one is reverse-engineered later - see matching comments on
    // SettingControl in iOS WheelDeviceSettingsView.swift and AlertEditorView.swift's
    // writableCommandCapabilities, and the writeSpec = null LeaperKimAnalysis.kt entry
    // on the Android side.
    SettingDefinition("gyro_calibration", "Gyro calibration", "Калибровка гироскопа", 56, null, 0, 255),
    SettingDefinition("transport_mode", "Transport mode", "Транспортировочный режим", 57, 22, 0, 1, "toggle"),
    SettingDefinition("unit", "Units", "Единицы", 58, 23, 0, 1, "unit"),
    SettingDefinition("voltage_correction", "Voltage correction", "Коррекция напряжения", 59, 24, -15, 15),
    SettingDefinition("low_voltage_mode", "Low battery mode", "Режим низкой батареи", 60, 25, 0, 1, "toggle"),
    SettingDefinition("high_speed_mode", "High speed mode", "Высокоскоростной режим", 61, 26, 0, 1, "toggle"),
    SettingDefinition("angle_trim", "Pedal angle trim", "Угол педалей", 0, 16, -80, 80, isDerived = true),
    SettingDefinition("key_tone", "Button tone volume", "Громкость кнопок", 63, 28, 0, 100),
    SettingDefinition("max_charge_voltage", "Max charge voltage", "Макс. напряжение зарядки", 64, 29, 0, 120),
    SettingDefinition("acc_dec_helper", "Acceleration/deceleration assist", "Помощь разгона/торможения", 66, 31, 0, 100),
    SettingDefinition("acc_reduction", "Accelerometer reduction", "Снижение акселерометра", 68, 33, 0, 100),
    SettingDefinition("brake_overpressure_alarm", "Brake overpressure alarm", "Тревога давления торможения", 69, 34, 80, 125),
)

private fun ByteArray.indexOfMarker(): Int {
    for (index in 0..size - 3) {
        if (this[index] == 0xDC.toByte() &&
            this[index + 1] == 0x5A.toByte() &&
            this[index + 2] == 0x5C.toByte()
        ) {
            return index
        }
    }
    return -1
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.u8OrNull(offset: Int): Int? =
    if (offset in indices) u8(offset) else null

private fun ByteArray.uint16Be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

private fun ByteArray.uint16BeOrNull(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) uint16Be(offset) else null

private fun ByteArray.int16BeOrNull(offset: Int): Int? {
    val value = uint16BeOrNull(offset) ?: return null
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun ByteArray.int16Be(offset: Int): Int {
    val value = uint16Be(offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun ByteArray.signedByte(offset: Int): Int {
    val value = u8(offset)
    return if (value >= 0x80) value - 0x100 else value
}

private fun ByteArray.distance(offset: Int): Double =
    uint16Be(offset + 2) * 65_536.0 + uint16Be(offset)

private fun ByteArray.withCrc32(): ByteArray {
    val value = crc32()
    return this + byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

private const val CRC32_SIZE = 4
private const val LegacyFrameSize = 36
private val LegacyFrameLengthRange = 20..46

private fun ByteArray.hasValidCrc32(): Boolean {
    if (size < 8) {
        return false
    }
    val expected = (u8(size - 4).toLong() shl 24) or
        (u8(size - 3).toLong() shl 16) or
        (u8(size - 2).toLong() shl 8) or
        u8(size - 1).toLong()
    return copyOfRange(0, size - 4).crc32() == expected
}

private fun ByteArray.crc32(): Long {
    var crc = 0xFFFFFFFFL
    for (byte in this) {
        crc = crc xor (byte.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1L != 0L) {
                (crc ushr 1) xor 0xEDB88320L
            } else {
                crc ushr 1
            }
        }
    }
    return crc xor 0xFFFFFFFFL
}

@ExperimentalWriteApi
fun buildLeaperKimLowLightCommand(enabled: Boolean): ByteArray {
    val text = if (enabled) "SetLightON" else "SetLightOFF"
    return text.encodeToByteArray()
}
