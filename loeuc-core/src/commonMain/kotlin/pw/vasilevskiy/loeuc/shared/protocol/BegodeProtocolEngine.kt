package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

data class BegodeTelemetry(
    val speedKmh: Double = Double.NaN,
    val pwmPercent: Double = Double.NaN,
    val voltage: Double = Double.NaN,
    val current: Double = Double.NaN,
    val phaseCurrent: Double = Double.NaN,
    val power: Double = Double.NaN,
    val apparentPower: Double = Double.NaN,
    val temperature: Double = Double.NaN,
    val motorTemperature: Double = Double.NaN,
    val batteryPercent: Double = Double.NaN,
    val tripDistanceMeters: Double = Double.NaN,
    val totalDistanceKm: Double = Double.NaN,
    val rideMode: Double = Double.NaN,
    val cellVoltages: List<Double> = emptyList(),
    /** Raw alarm bitmask reported by the wheel itself, see [BegodeWheelAlert]. */
    val wheelAlertFlags: Int = 0,
    val lastType: Int = -1,
    val lastPage: Int = -1,
    val isLegacy: Boolean = false,
)

/**
 * Bits of the wheel's own alarm byte (`type=4,page=24 @14`), matching WheelLog's
 * `GotwayAdapter` decode. These are the alarms the wheel raises for itself, independent of
 * any threshold the app evaluates - [HighPower] in particular is the stock Begode alarm that
 * fires at [BegodeProtocolEngine.WHEEL_HIGH_POWER_ALARM_PWM_FRACTION] of motor PWM.
 */
object BegodeWheelAlert {
    const val HighPower: Int = 1 shl 0
    const val Speed2: Int = 1 shl 1
    const val Speed1: Int = 1 shl 2
    const val LowVoltage: Int = 1 shl 3
    const val OverVoltage: Int = 1 shl 4
    const val OverTemperature: Int = 1 shl 5
    const val HallSensorError: Int = 1 shl 6
    const val TransportMode: Int = 1 shl 7
}

class BegodeProtocolEngine {
    private var buffer = byteArrayOf()
    private val batteryCells = mutableMapOf<Int, Double>()
    private var legacyMaxVoltage: Double = 100.8
    private var legacyVoltageScale: Double = legacyMaxVoltage / LEGACY_BASE_MAX_VOLTAGE
    private var legacyPwmFreeSpinSpeedKmh: Double = legacyDefaultFreeSpinSpeedKmh(legacyMaxVoltage)
    private var legacyPwmFreeSpinVoltage: Double = legacyMaxVoltage
    private var legacyPwmPowerFactor: Double = LEGACY_DEFAULT_POWER_FACTOR
    private var modernProtocolSeen = false
    private var maxSeriesCellIndex = -1
    private var modernPackVoltageSeen = false
    private var decodedModelName: String? = null

    // Series-cell count of the pack: the model's published pack string, handed down by the
    // caller. There is no fallback guess - see calculateBatteryPercent().
    private var catalogueSeriesCells = 0

    // Per-device no-load ratio (km/h per volt) learned from the live stream, overriding the
    // voltage-class default when the wheel itself proves it wrong. See observeNoLoadEvidence()
    // and calibrateFromHighPowerAlarm().
    private var learnedNoLoadRatio: Double? = null
    private val noLoadEvidence = ArrayDeque<Double>()
    private var highPowerAlarmActive = false

    // Accumulated state to support multi-frame telemetry (Type/Page system)
    private var state = BegodeTelemetry()

    fun reset() {
        buffer = byteArrayOf()
        batteryCells.clear()
        modernProtocolSeen = false
        maxSeriesCellIndex = -1
        modernPackVoltageSeen = false
        decodedModelName = null
        catalogueSeriesCells = 0
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
        highPowerAlarmActive = false
        state = BegodeTelemetry()
    }

    fun setLegacyVoltageClassMaxVoltage(maxVoltage: Double) {
        legacyMaxVoltage = maxVoltage
        legacyVoltageScale = maxVoltage / LEGACY_BASE_MAX_VOLTAGE
        legacyPwmFreeSpinSpeedKmh = legacyDefaultFreeSpinSpeedKmh(maxVoltage)
        legacyPwmFreeSpinVoltage = maxVoltage
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
    }

    /**
     * How many cells the pack has in series, from the retail catalogue entry for this model.
     *
     * Wheels without a smart BMS never report their cells, and the charge scale is the pack
     * voltage divided by this number - so getting it wrong moves the whole scale. It cannot be
     * derived from a single voltage reading: a full 36S reads 151.2 V and so does a 40S at
     * 3.78 V per cell. The caller resolves the model against the catalogue - or takes the rider's
     * own pick - and pushes the answer down here. Without it the charge stays unknown rather than
     * being guessed; see [calculateBatteryPercent].
     *
     * Ignored for the legacy voltage-class path, where the rider's own choice of pack class is
     * the better answer. Zero or a nonsensical count clears it.
     */
    fun setPackSeriesCells(seriesCells: Int) {
        catalogueSeriesCells = if (seriesCells in MIN_SERIES_CELLS..MAX_SERIES_CELLS) seriesCells else 0
    }

    /** Series-cell count the charge scale currently runs on, or `0` while nothing is known. */
    fun seriesCellCount(): Int = catalogueSeriesCells

    fun modelName(): String? = decodedModelName

    /**
     * No-load ratio the PWM estimate currently runs on, in km/h per volt: either the voltage
     * class default, the value handed to [setLegacyPwmCalibration], or whatever the live stream
     * has since proven (see [observeNoLoadEvidence], [calibrateFromHighPowerAlarm]).
     */
    fun legacyPwmNoLoadRatio(): Double = learnedNoLoadRatio ?: configuredNoLoadRatio()

    fun setLegacyPwmCalibration(
        freeSpinSpeedKmh: Double,
        freeSpinVoltage: Double = legacyMaxVoltage,
        powerFactor: Double = 1.0,
    ) {
        if (freeSpinSpeedKmh.isFinite() && freeSpinSpeedKmh > 0.0) {
            legacyPwmFreeSpinSpeedKmh = freeSpinSpeedKmh
        }
        if (freeSpinVoltage.isFinite() && freeSpinVoltage > 0.0) {
            legacyPwmFreeSpinVoltage = freeSpinVoltage
        }
        if (powerFactor.isFinite() && powerFactor > 0.0) {
            legacyPwmPowerFactor = powerFactor
        }
        // An explicit calibration supersedes anything learned from the previous baseline.
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
    }

    fun consume(chunk: ByteArray): BegodeTelemetry? {
        chunk.begodeNameResponse()?.let { decodedModelName = it }
        buffer += chunk
        var updated = false

        while (buffer.size >= BEGODE_FRAME_SIZE) {
            val headerIndex = buffer.indexOfHeader()
            if (headerIndex < 0) {
                buffer = buffer.takeLast(1).toByteArray()
                break
            }
            if (headerIndex > 0) {
                buffer = buffer.copyOfRange(headerIndex, buffer.size)
            }
            if (buffer.size < BEGODE_FRAME_SIZE) break

            // Check if we have a valid 24-byte frame
            if (buffer.hasFooter()) {
                val frame = buffer.copyOfRange(0, BEGODE_FRAME_SIZE)
                processFrame(frame)
                updated = true
                buffer = buffer.copyOfRange(BEGODE_FRAME_SIZE, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }

        return if (updated) state else null
    }

    private fun processFrame(frame: ByteArray) {
        val type = frame.u8(18)
        val page = frame.u8(19)

        // Update BMS cells if present (common for modern Begode with Smart BMS)
        if (type in 2..3 && page in 0..7) {
            val packOffset = if (type == 3) 64 else 0
            val pageOffset = page * 8
            repeat(8) { index ->
                val voltage = frame.uint16Be(2 + index * 2)
                if (voltage in 2500..4500) {
                    batteryCells[packOffset + pageOffset + index] = voltage / 1000.0
                    maxSeriesCellIndex = maxOf(maxSeriesCellIndex, pageOffset + index)
                }
            }
        }

        if (isModernIdentityFrame(frame, type, page)) {
            modernProtocolSeen = true
        }

        if (isModernFrame(frame, type, page)) {
            state = parseModern(frame, type, page)
            return
        }

        if (isLegacyStatusFrame(frame)) {
            state = parseLegacyStatus(frame)
            return
        }

        if (isLegacyLiveTelemetryFrame(frame)) {
            state = parseLegacy(frame)
        }
    }

    private fun parseModern(frame: ByteArray, type: Int, page: Int): BegodeTelemetry {
        var next = state.copy(lastType = type, lastPage = page)

        when (type) {
            0 -> if (page == 24) {
                next = next.copy(
                    speedKmh = (abs(frame.int16Be(4)).toDouble() * 3.6) / 100.0,
                    // Motor/phase current from the main live frame. A moving Commander GT Pro
                    // dump reaches -100.4..138.9 A here while the battery current in type 7 only
                    // reaches -4.78 A. Deriving phase current by dividing battery current by PWM
                    // was especially wrong at 0% PWM, where idle noise became a bogus 10+ A.
                    phaseCurrent = abs(frame.int16Be(10)) / 100.0,
                    // The traditional Begode thermistor conversion. On Commander GT Pro this
                    // channel sits around 33 C and moves under controller load, so expose it as
                    // the primary/controller temperature.
                    temperature = frame.int16Be(12) / 340.0 + 36.53,
                    tripDistanceMeters = frame.uint16Be(8).toDouble(),
                    isLegacy = false,
                )
            }
            4 -> if (page == 24) {
                next = next.copy(
                    totalDistanceKm = frame.totalDistanceKm(),
                    rideMode = ((frame.uint16Be(6) ushr 13) and 0x7).toDouble(),
                    wheelAlertFlags = frame.u8(14),
                    isLegacy = false,
                )
            }
            1 -> {
                val voltage = frame.uint16Be(6) / 10.0
                modernPackVoltageSeen = true
                next = next.copy(
                    voltage = voltage,
                    batteryPercent = calculateBatteryPercent(voltage, batteryCells.values.toList(), isLegacy = false),
                    isLegacy = false,
                )
            }
            2, 3 -> {
                val cells = batteryCells.values.sorted()
                val voltage = if (modernPackVoltageSeen && next.voltage.isFinite()) {
                    next.voltage
                } else {
                    cells.takeIf { it.isNotEmpty() }?.average()?.times(inferredSeriesCellCount()) ?: Double.NaN
                }
                next = next.copy(
                    voltage = voltage,
                    batteryPercent = calculateBatteryPercent(voltage, cells, isLegacy = false),
                    cellVoltages = cells,
                    isLegacy = false,
                )
            }
            7 -> if (page == 24) {
                val current = frame.int16Be(2) / 100.0
                val voltage = if (next.voltage.isNaN()) 100.0 else next.voltage
                val pwm = frame.int16Be(8).toDouble()
                next = next.copy(
                    current = current,
                    power = voltage * current,
                    apparentPower = voltage * current,
                    // Commander GT Pro reports a separate, cooler 20 C channel here. This is the
                    // motor temperature; keeping it separate makes both temperatures visible.
                    motorTemperature = frame.int16Be(6).toDouble(),
                    pwmPercent = pwm,
                    isLegacy = false,
                )
            }
        }
        return next
    }

    private fun parseLegacy(frame: ByteArray): BegodeTelemetry {
        val speedRaw = frame.int16Be(4)
        val baseVoltage = frame.uint16Be(2) / 100.0
        val voltage = if (baseVoltage in 45.0..70.0) baseVoltage * legacyVoltageScale else frame.uint16Be(16).toDouble()
        val phaseCurrentRaw = frame.int16Be(10)
        val phaseCurrent = abs(phaseCurrentRaw) / 100.0
        val speedKmh = if (abs(speedRaw) <= LEGACY_STATIONARY_SPEED_DEADBAND_RAW) {
            0.0
        } else {
            (abs(speedRaw) * 3.6) / 100.0
        }

        // Deliberately the magnitude: the PWM model adds a resistive-drop term and the no-load
        // detector compares against a ceiling, and both would read a braking sample as a lightly
        // loaded one. Reading PWM high under braking is the safe direction to be wrong in.
        observeNoLoadEvidence(speedKmh, voltage, phaseCurrent)
        val pwm = calculateLegacyPwmPercent(speedKmh, voltage, phaseCurrent)
        val flow = powerFlowSign(phaseCurrentRaw, speedRaw, TorqueSignConvention.AgreementIsDriving)
        return state.copy(
            speedKmh = speedKmh,
            pwmPercent = pwm,
            voltage = voltage,
            current = flow * phaseCurrent * (pwm / 100.0), // Estimated battery current
            phaseCurrent = phaseCurrent,
            power = flow * voltage * phaseCurrent * (pwm / 100.0),
            // Apparent power is a magnitude by definition, so it keeps no sign.
            apparentPower = voltage * phaseCurrent * (pwm / 100.0),
            temperature = frame.int16Be(12) / 340.0 + 36.53,
            batteryPercent = calculateBatteryPercent(voltage, emptyList(), isLegacy = true),
            // Raw meters, exactly as WheelLog's GotwayAdapter reads it (`shortFromBytesBE(buff, 8)`
            // straight into setWheelDistance). The old `/ 3.6` here was inferred from a ride that
            // displayed "45 km for a 12.5 km trip", but that number came from the ride summary
            // differencing @8 of frame B - the power-off timer, not an odometer - so the 3.6 was a
            // coincidence. See docs/begode-protocol-notes.md.
            tripDistanceMeters = frame.uint16Be(8).toDouble(),
            lastType = frame.u8(18),
            lastPage = frame.u8(19),
            isLegacy = true,
        )
    }

    private fun parseLegacyStatus(frame: ByteArray): BegodeTelemetry {
        // Frame B carries the odometer as a 32-bit metre count at @2..@5 on legacy streams just
        // like it does on modern ones. @8 is the auto-power-off countdown in seconds (it idles at
        // 7200 = two hours and ticks down while the wheel stands still) and @10 is the tiltback
        // speed - neither is distance or charge, though both used to be read as such here.
        val alertFlags = frame.u8(14)
        calibrateFromHighPowerAlarm(alertFlags and BegodeWheelAlert.HighPower != 0)
        return state.copy(
            totalDistanceKm = frame.totalDistanceKm(),
            wheelAlertFlags = alertFlags,
            lastType = frame.u8(18),
            lastPage = frame.u8(19),
            isLegacy = true,
        )
    }

    private fun configuredNoLoadRatio(): Double = legacyPwmFreeSpinSpeedKmh / legacyPwmFreeSpinVoltage

    private fun calculateLegacyPwmPercent(speedKmh: Double, voltage: Double, phaseCurrent: Double): Double {
        if (!speedKmh.isFinite() || !voltage.isFinite() || voltage <= 0.0) {
            return 0.0
        }
        val fraction = legacyPwmFraction(speedKmh, voltage, phaseCurrent) ?: return Double.NaN
        // Deliberately reported a touch high: the estimate has no direct measurement behind it,
        // and reading low means the rider is warned late.
        return (fraction * LEGACY_PWM_SAFETY_MARGIN * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * WheelLog's model (`WheelData.calculatePwm`): speed over the no-load speed the pack can spin
     * to at this voltage, plus a load term for the resistive drop the free-spin ratio can't see.
     * Returns the raw fraction with no safety margin, so the calibration paths can invert it.
     */
    private fun legacyPwmFraction(speedKmh: Double, voltage: Double, phaseCurrent: Double): Double? {
        val noLoadAtVoltage = legacyPwmNoLoadRatio() * voltage * legacyPwmPowerFactor
        if (!noLoadAtVoltage.isFinite() || noLoadAtVoltage <= 0.0) {
            return null
        }
        val speedRatio = speedKmh.coerceAtLeast(0.0) / noLoadAtVoltage
        return speedRatio + legacyLoadCompensation(voltage, phaseCurrent)
    }

    /** Iphase * R / Vbat, with R the estimated phase resistance. */
    private fun legacyLoadCompensation(voltage: Double, phaseCurrent: Double): Double {
        if (!phaseCurrent.isFinite() || voltage <= 0.0) {
            return 0.0
        }
        return (phaseCurrent * LEGACY_ESTIMATED_PHASE_RESISTANCE) / voltage
    }

    /**
     * Fallback 2: the wheel's own high-power alarm fires at a known PWM, so the moment it turns on
     * is a direct reading of the model's only unknown. Solves the model for the no-load ratio and
     * takes the answer only if it lands in a sane band around the voltage-class default - a
     * recalibration that disagrees wildly is garbage (a stale speed sample, an alarm raised for
     * something other than PWM saturation) and gets dropped rather than trusted.
     */
    private fun calibrateFromHighPowerAlarm(active: Boolean) {
        val rising = active && !highPowerAlarmActive
        highPowerAlarmActive = active
        if (!rising || modernProtocolSeen) {
            return
        }
        val speedKmh = state.speedKmh
        val voltage = state.voltage
        if (!speedKmh.isFinite() || speedKmh < MIN_CALIBRATION_SPEED_KMH) {
            return
        }
        if (!voltage.isFinite() || voltage <= 0.0 || legacyPwmPowerFactor <= 0.0) {
            return
        }
        val speedShare = WHEEL_HIGH_POWER_ALARM_PWM_FRACTION - legacyLoadCompensation(voltage, state.phaseCurrent)
        if (speedShare < MIN_ALARM_SPEED_SHARE) {
            return
        }
        acceptNoLoadRatio(speedKmh / (speedShare * voltage * legacyPwmPowerFactor))
    }

    /**
     * PWM cannot exceed 100%, so a wheel seen holding a given speed at a given voltage puts a hard
     * floor under its no-load ratio. Raises the calibration to that floor once the stream sustains
     * it, which keeps one corrupt speed sample from silently muting the PWM estimate for the rest
     * of the ride. Only ever raises: lowering it needs the wheel's own alarm as evidence.
     */
    private fun observeNoLoadEvidence(speedKmh: Double, voltage: Double, phaseCurrent: Double) {
        if (speedKmh < MIN_CALIBRATION_SPEED_KMH || !voltage.isFinite() || voltage <= 0.0 ||
            phaseCurrent > MAX_NO_LOAD_EVIDENCE_PHASE_CURRENT || legacyPwmPowerFactor <= 0.0
        ) {
            noLoadEvidence.clear()
            return
        }
        val floor = speedKmh / (voltage * legacyPwmPowerFactor)
        if (floor <= legacyPwmNoLoadRatio()) {
            noLoadEvidence.clear()
            return
        }
        noLoadEvidence.addLast(floor)
        if (noLoadEvidence.size < NO_LOAD_EVIDENCE_SAMPLES) {
            return
        }
        // The weakest sample of the run, so the ratio moves no further than every sample supports.
        val sustained = noLoadEvidence.minOrNull()
        noLoadEvidence.clear()
        if (sustained != null) {
            acceptNoLoadRatio(sustained)
        }
    }

    private fun acceptNoLoadRatio(candidate: Double) {
        if (!candidate.isFinite() || candidate <= 0.0) {
            return
        }
        val default = configuredNoLoadRatio()
        if (!default.isFinite() || default <= 0.0) {
            return
        }
        if (candidate < default * MIN_CALIBRATION_FACTOR || candidate > default * MAX_CALIBRATION_FACTOR) {
            return
        }
        learnedNoLoadRatio = candidate
    }

    /**
     * Reference points on the per-cell discharge curve of a lithium cell.
     *
     * This used to return the tabulated values themselves, so charge moved in steps of 10%:
     * the number held while the voltage crept within a step, then jumped by ten at the
     * boundary. Values between points are interpolated linearly now.
     *
     * The bottom point is 3.00 V rather than 3.35 because the stepped version collapsed
     * everything below 3.35 to zero, and a cell at 3.2 V is not empty.
     */
    private val batteryCellVoltageCurve = listOf(
        3.00 to 0.0,
        3.35 to 10.0,
        3.45 to 20.0,
        3.55 to 30.0,
        3.62 to 40.0,
        3.70 to 50.0,
        3.78 to 60.0,
        3.85 to 70.0,
        3.95 to 80.0,
        4.05 to 90.0,
        4.15 to 100.0,
    )

    internal fun batteryPercentFromCellVoltage(cellVoltage: Double): Double {
        if (cellVoltage.isNaN()) return Double.NaN
        val first = batteryCellVoltageCurve.first()
        val last = batteryCellVoltageCurve.last()
        if (cellVoltage <= first.first) return first.second
        if (cellVoltage >= last.first) return last.second

        val upperIndex = batteryCellVoltageCurve.indexOfFirst { cellVoltage <= it.first }
        val (lowerVoltage, lowerPercent) = batteryCellVoltageCurve[upperIndex - 1]
        val (upperVoltage, upperPercent) = batteryCellVoltageCurve[upperIndex]
        val span = upperVoltage - lowerVoltage
        if (span <= 0.0) return lowerPercent
        return lowerPercent + (upperPercent - lowerPercent) * (cellVoltage - lowerVoltage) / span
    }

    /**
     * Charge from the pack voltage, which is only meaningful once the series-cell count is known.
     *
     * There is no guess here on purpose. The count used to be inferred from the live pack voltage
     * by buckets, and that is a trap rather than an approximation: the buckets stepped at 90 V,
     * which sits in the middle of a 24S pack's working range, so every sag under that line moved
     * the divider from 24 to 20 and the reported charge jumped from about 53 % to a clipped 100 %.
     * A rider who connected below the line - a T4 at half charge - saw a full battery for the
     * whole session. Two layers of latching were built on top to hold the guess steady, and
     * neither survived a reconnect.
     *
     * The count cannot be derived from one voltage reading even in principle: a full 36S reads
     * 151.2 V and so does a 40S at 3.78 V per cell. It comes from the pack itself (a smart BMS
     * reporting cells), or from the catalogue entry for the model, and when neither is available
     * the honest answer is that the charge is unknown.
     */
    private fun calculateBatteryPercent(voltage: Double, cells: List<Double>, isLegacy: Boolean): Double {
        val cellVoltage = if (cells.isNotEmpty()) cells.average() else {
            // Legacy wheels have a fixed, rider-selected voltage class (legacyMaxVoltage), so the
            // series-cell count stays pinned to that class rather than being re-derived from the
            // live pack voltage.
            val series = if (isLegacy && legacyMaxVoltage > 0.0) {
                (legacyMaxVoltage / 4.2).roundToInt()
            } else {
                catalogueSeriesCells
            }
            if (series <= 0) return Double.NaN
            voltage / series
        }

        return batteryPercentFromCellVoltage(cellVoltage)
    }

    /**
     * Series-cell count used to rebuild the pack voltage from cell readings. Measured cells win
     * over the catalogue - they are the pack itself rather than its data sheet - and the
     * catalogue wins over the blanket default, which is a 32S guess that suits no 24S wheel.
     */
    private fun inferredSeriesCellCount(): Int {
        val nominal = if (catalogueSeriesCells > 0) catalogueSeriesCells else DEFAULT_SERIES_CELL_COUNT
        return maxOf(nominal, maxSeriesCellIndex + 1)
    }

    private fun ByteArray.indexOfHeader(): Int {
        for (i in 0 until size - 1) {
            if (this[i] == 0x55.toByte() && this[i + 1] == 0xAA.toByte()) return i
        }
        return -1
    }

    private fun ByteArray.begodeNameResponse(): String? {
        val prefix = "NAME:".encodeToByteArray()
        if (size < prefix.size || !prefix.indices.all { this[it] == prefix[it] }) return null
        return copyOfRange(prefix.size, size)
            .decodeToString()
            .trim { it == '\u0000' || it == '\r' || it == '\n' || it == ' ' }
            .takeIf { it.isNotEmpty() }
    }

    private fun ByteArray.hasFooter(): Boolean {
        if (size < BEGODE_FRAME_SIZE) return false
        return this[20] == 0x5A.toByte() && this[21] == 0x5A.toByte() &&
               this[22] == 0x5A.toByte() && this[23] == 0x5A.toByte()
    }

    private fun isModernFrame(frame: ByteArray, type: Int, page: Int): Boolean {
        return when (type) {
            0 -> modernProtocolSeen && page == 24 && isModernSpeedFrame(frame)
                || page == 24 && isModernSpeedFrame(frame) && !isLegacyLiveTelemetryFrame(frame)
            1 -> isModernIdentityFrame(frame, type, page)
            2, 3 -> isModernIdentityFrame(frame, type, page)
            4 -> modernProtocolSeen && page == 24
            7 -> page == 24 && (modernProtocolSeen || !isLegacyLiveTelemetryFrame(frame))
            else -> false
        }
    }

    private fun isModernIdentityFrame(frame: ByteArray, type: Int, page: Int): Boolean {
        return when (type) {
            1 -> page in 0..3 && modernPackVoltage(frame).isFinite()
            2, 3 -> page in 0..7 && hasCellVoltages(frame)
            else -> false
        }
    }

    private fun isModernSpeedFrame(frame: ByteArray): Boolean {
        return abs(frame.int16Be(4)) <= MAX_SPEED_RAW
    }

    private fun isLegacyLiveTelemetryFrame(frame: ByteArray): Boolean {
        // Frame kind comes from the type/page bytes, never from the payload looking plausible -
        // every other frame carries a distance or cell voltage at @4 that reads as a fine speed.
        if (frame.u8(18) != 0 || frame.u8(19) != 24) {
            return false
        }
        val speedRaw = abs(frame.int16Be(4))
        if (speedRaw > MAX_SPEED_RAW) {
            return false
        }
        val baseVoltage = frame.uint16Be(2) / 100.0
        val fallbackVoltage = frame.uint16Be(16).toDouble()
        return baseVoltage in 45.0..70.0 || fallbackVoltage in 45.0..120.0
    }

    private fun isLegacyStatusFrame(frame: ByteArray): Boolean {
        return frame.u8(18) == 4 && frame.u8(19) == 24 && !modernProtocolSeen
    }

    private fun modernPackVoltage(frame: ByteArray): Double {
        return (frame.uint16Be(6) / 10.0).takeIf { it in 70.0..250.0 } ?: Double.NaN
    }

    private fun hasCellVoltages(frame: ByteArray): Boolean {
        return (0 until 8).any { index ->
            frame.uint16Be(2 + index * 2) in 2500..4500
        }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
    private fun ByteArray.uint16Be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
    private fun ByteArray.int16Be(offset: Int): Int {
        val v = uint16Be(offset)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun ByteArray.totalDistanceKm(): Double {
        val high = uint16Be(2).toLong()
        val low = uint16Be(4).toLong()
        return ((high shl 16) + low) / 1000.0
    }

    companion object {
        private const val BEGODE_FRAME_SIZE = 24
        private const val MAX_SPEED_RAW = 4_500
        private const val LEGACY_BASE_MAX_VOLTAGE = 67.2
        private const val LEGACY_STATIONARY_SPEED_DEADBAND_RAW = 100
        private const val DEFAULT_SERIES_CELL_COUNT = 32

        // Sanity bounds on a pushed-down series-cell count: below is no unicycle pack, above is
        // not one either, and either way it is a caller's mistake rather than a pack.
        private const val MIN_SERIES_CELLS = 10
        private const val MAX_SERIES_CELLS = 60
        private const val LEGACY_ESTIMATED_PHASE_RESISTANCE = 0.05

        /** WheelLog's default for the same model; the free-spin ratio alone reads optimistically. */
        private const val LEGACY_DEFAULT_POWER_FACTOR = 0.9

        /** Report the estimate 1% high rather than risk warning the rider late. */
        private const val LEGACY_PWM_SAFETY_MARGIN = 1.01

        /** Motor PWM at which stock Begode firmware raises [BegodeWheelAlert.HighPower]. */
        const val WHEEL_HIGH_POWER_ALARM_PWM_FRACTION = 0.8

        /**
         * Below this the wheel is manoeuvring rather than saturating the motor, and the speed
         * sample is too small for the model inversion to say anything useful.
         */
        private const val MIN_CALIBRATION_SPEED_KMH = 25.0

        /** Leaves the alarm inversion room to be about speed rather than about the load term. */
        private const val MIN_ALARM_SPEED_SHARE = 0.3

        /** Above this the load term dominates and the 100%-PWM ceiling stops being a clean floor. */
        private const val MAX_NO_LOAD_EVIDENCE_PHASE_CURRENT = 25.0

        /** Consecutive samples a higher no-load floor must survive before it is believed. */
        private const val NO_LOAD_EVIDENCE_SAMPLES = 3

        // Band a recalibration has to land in to be taken at all. Wide enough for the spread
        // between motor variants inside one voltage class, narrow enough that a bogus reading
        // cannot walk the PWM estimate somewhere absurd.
        private const val MIN_CALIBRATION_FACTOR = 0.7
        private const val MAX_CALIBRATION_FACTOR = 1.5

        /**
         * Lift-up (free-spin) speed at the class's own maximum voltage, WheelLog's "rotation
         * speed" setting. The 100.8 V entry is measured: recorded rides from a GotWay_50763
         * (Begode RS) have the wheel spinning at 100.8 km/h on 99.0 V drawing 8..15 A, and its
         * speed tracks GPS within 1.3% across 843 matched points, so 100.8/99.0 * 100.8 is a lower
         * bound on free spin. That is one RS, though - slower motors on the same voltage class
         * (MSX, Nikola) will read low here, as will every unmeasured class, which is what the
         * per-device calibration above is for.
         */
        private fun legacyDefaultFreeSpinSpeedKmh(maxVoltage: Double): Double {
            return when {
                maxVoltage <= 67.2 -> 44.0
                maxVoltage <= 84.0 -> 79.0
                maxVoltage <= 100.8 -> 102.7
                maxVoltage <= 116.8 -> 95.0
                maxVoltage <= 134.4 -> 113.0
                else -> 140.0
            }
        }
    }
}
