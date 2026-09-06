package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BegodeProtocolEngineTest {
    @Test
    fun capturesModelNameResponseOutsideBinaryTelemetryFrames() {
        val engine = BegodeProtocolEngine()

        engine.consume("NAME:ET MAX\r\n".encodeToByteArray())

        assertEquals("ET MAX", engine.modelName())
    }

    @Test
    fun modernBegodeKeepsHardwarePwmAsPercent() {
        val engine = BegodeProtocolEngine()
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 1_220)
        }
        val power = begodeFrame(type = 7, page = 24).apply {
            putInt16Be(offset = 2, value = -250)
            putInt16Be(offset = 6, value = 29)
            putInt16Be(offset = 8, value = 73)
        }

        engine.consume(battery)
        val telemetry = requireNotNull(engine.consume(power))

        assertFalse(telemetry.isLegacy)
        assertEquals(122.0, telemetry.voltage, 0.0001)
        assertEquals(-2.5, telemetry.current, 0.0001)
        assertEquals(73.0, telemetry.pwmPercent, 0.0001)
    }

    @Test
    fun commanderGtProKeepsMeasuredPhaseCurrentSeparateFromBatteryCurrent() {
        val engine = BegodeProtocolEngine()
        val main = byteArrayOf(
            0x55, 0xAA.toByte(), 0x18, 0x0C, 0x00, 0x5A, 0x00, 0x5E,
            0x1D, 0xB2.toByte(), 0x36, 0x42, 0xFB.toByte(), 0x95.toByte(), 0x14, 0xA9.toByte(),
            0x00, 0x09, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
        )
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 1_540)
        }
        val power = begodeFrame(type = 7, page = 24).apply {
            putInt16Be(offset = 2, value = -478)
            putInt16Be(offset = 6, value = 20)
            putInt16Be(offset = 8, value = 12)
        }

        engine.consume(battery)
        engine.consume(main)
        val telemetry = requireNotNull(engine.consume(power))

        assertEquals(-4.78, telemetry.current, 0.0001)
        assertEquals(138.9, telemetry.phaseCurrent, 0.0001)
        assertEquals(12.0, telemetry.pwmPercent, 0.0001)
        assertEquals(33.2035, telemetry.temperature, 0.0001)
        assertEquals(20.0, telemetry.motorTemperature, 0.0001)
    }

    @Test
    fun modernBegodeFallsBackToSmartBmsCellVoltage() {
        val engine = BegodeProtocolEngine()
        var telemetry: BegodeTelemetry? = null

        repeat(7) { page ->
            val frame = begodeFrame(type = 2, page = page).apply {
                repeat(8) { index ->
                    val cellIndex = page * 8 + index
                    putUInt16Be(
                        offset = 2 + index * 2,
                        value = if (cellIndex < 50) 4_196 else 0,
                    )
                }
            }
            telemetry = engine.consume(frame)
        }

        val value = requireNotNull(telemetry)
        assertFalse(value.isLegacy)
        assertEquals(50, value.cellVoltages.size)
        assertEquals(209.8, value.voltage, 0.0001)
        assertEquals(100.0, value.batteryPercent, 0.0001)
    }

    @Test
    fun modernBegodeChargeStaysUnknownWithoutASeriesCellCount() {
        val engine = BegodeProtocolEngine().apply {
            // A device that never had its (legacy-only) voltage class touched, still on the
            // 100.8V default. That default is nobody's choice and must not become a divider:
            // this pack reads 150V, which 100.8/4.2 = 24S would call 6.25V per cell and clip
            // to a full battery.
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 1_500) // 150.0V, no cell frames received yet
        }

        val telemetry = requireNotNull(engine.consume(battery))

        assertFalse(telemetry.isLegacy)
        assertEquals(150.0, telemetry.voltage, 0.0001)
        // 150V is a full 36S and equally a 40S at 3.75V per cell. Nothing here can tell them
        // apart, so the charge is unknown rather than invented.
        assertTrue(telemetry.batteryPercent.isNaN())
    }

    @Test
    fun smartBmsCellsOutrankTheCatalogueCountAndNeedNoModelAtAll() {
        // Current Begodes report their own cells (type=2/3 frames), and then no model is
        // needed for charge at all: the pack is measured rather than inferred. The catalogue
        // count here is deliberately wrong - had it won, 185V over 24 cells would read
        // 7.7V per cell and clip to a full battery.
        val engine = BegodeProtocolEngine().apply { setPackSeriesCells(24) }
        var telemetry: BegodeTelemetry? = null

        repeat(7) { page ->
            val frame = begodeFrame(type = 2, page = page).apply {
                repeat(8) { index ->
                    val cellIndex = page * 8 + index
                    // 3.70V per cell is exactly 50% on the discharge curve.
                    putUInt16Be(offset = 2 + index * 2, value = if (cellIndex < 50) 3_700 else 0)
                }
            }
            telemetry = engine.consume(frame)
        }

        val value = requireNotNull(telemetry)
        assertEquals(50, value.cellVoltages.size)
        assertEquals(185.0, value.voltage, 0.0001)
        assertEquals(50.0, value.batteryPercent, 0.0001)
    }

    @Test
    fun modernBegodeChargeSurvivesReconnectingOnAHalfEmptyPack() {
        // The T4 Max report: the rider reconnects mid-ride with the pack already under 90V.
        // A fresh engine has no history, and the old voltage-bucket guess called anything under
        // 90V a 20S pack - 89.5/20 = 4.475V per cell, clipped to 100%, for the rest of the
        // session. With the catalogue count there is no history to lose.
        val engine = BegodeProtocolEngine().apply { setPackSeriesCells(24) }
        val frame = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 895) // 89.5V
        }

        val telemetry = requireNotNull(engine.consume(frame))

        // 89.5/24 = 3.7292V/cell -> 53.65% on the discharge curve.
        assertEquals(53.6458, telemetry.batteryPercent, 0.0001)
    }

    @Test
    fun modernBegodeUsesThePackSeriesCountItWasGivenInsteadOfGuessingFromVoltage() {
        val engine = BegodeProtocolEngine().apply {
            // What the retail catalogue says about a T4: 24S4P, 1800 Wh.
            setPackSeriesCells(24)
        }
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 891) // 89.1V, no cell frames received yet
        }

        val telemetry = requireNotNull(engine.consume(battery))

        // 89.1/24 = 3.7125V/cell -> 51.56%. The voltage buckets would have called this 20S,
        // 4.455V/cell, and clipped it to a full battery.
        assertEquals(51.5625, telemetry.batteryPercent, 0.0001)
    }

    @Test
    fun modernBegodeChargeOnlyFallsAsThePackDrains() {
        // The whole point of a fixed series-cell count: charge tracks the pack down without
        // stepping back up. The old voltage buckets broke exactly here, at the 90V line.
        val engine = BegodeProtocolEngine().apply { setPackSeriesCells(24) }

        val full = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 946) // 94.6V -> 79.17%
        }
        val sagging = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 891) // 89.1V, across the old bucket boundary
        }
        val emptier = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 855) // 85.5V
        }

        val start = requireNotNull(engine.consume(full))
        val dip = requireNotNull(engine.consume(sagging))
        val end = requireNotNull(engine.consume(emptier))

        assertEquals(79.1666, start.batteryPercent, 0.0001)
        assertEquals(51.5625, dip.batteryPercent, 0.0001)
        assertEquals(31.7857, end.batteryPercent, 0.0001)
        assertTrue(end.batteryPercent < dip.batteryPercent)
        assertTrue(dip.batteryPercent < start.batteryPercent)
    }

    @Test
    fun legacyBegodeKeepsTheRidersVoltageClassOverTheCatalogue() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(84.0)
            setPackSeriesCells(24)
        }
        val frame = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 5_000) // 50.00 * 1.25 = 62.5V
            putInt16Be(offset = 4, value = 100)
        }

        val telemetry = requireNotNull(engine.consume(frame))

        // 84V class -> 20S -> 62.5/20 = 3.125V/cell -> 3.57%. The rider picked the class by
        // hand; a catalogue guess must not overrule it.
        assertEquals(3.5714, telemetry.batteryPercent, 0.0001)
    }

    @Test
    fun legacyBegodeStationaryPwmIsCalculatedAsZero() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        val main = byteArrayOf(
            0x55, 0xAA.toByte(), 0x19, 0x92.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x3C, 0xF6.toByte(), 0xF3.toByte(), 0x00, 0x01,
            0x00, 0x4E, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
        )

        val telemetry = requireNotNull(engine.consume(main))

        assertTrue(telemetry.isLegacy)
        assertEquals(0.0, telemetry.speedKmh, 0.0001)
        assertEquals(98.19, telemetry.voltage, 0.01)
        assertTrue(telemetry.pwmPercent >= 0.0)
        assertEquals(29.72, telemetry.temperature, 0.01)
    }

    @Test
    fun legacyBegodeMovingPwmUsesCalculatedNoLoadRatio() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        val main = byteArrayOf(
            0x55, 0xAA.toByte(), 0x18, 0xD8.toByte(), 0xFD.toByte(), 0x6D,
            0x00, 0x00, 0x03, 0x15, 0xF4.toByte(), 0x84.toByte(), 0x08, 0x00,
            0x00, 0x01, 0x00, 0x51, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
        )

        val telemetry = requireNotNull(engine.consume(main))

        assertTrue(telemetry.isLegacy)
        assertEquals(23.724, telemetry.speedKmh, 0.0001)
        assertEquals(95.4, telemetry.voltage, 0.01)
        // 23.724 km/h against a no-load speed of 102.7/100.8 * 95.4 V * 0.9 power factor, plus the
        // 29.4 A load term, plus the 1% safety margin.
        assertEquals(28.95, telemetry.pwmPercent, 0.01)
    }

    @Test
    fun legacyBegodeTripDistanceUsesRawCounterDirectlyAsMeters() {
        val engine = BegodeProtocolEngine()
        val frame = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_000) // 60.00V, in the legacy 45..70V range
            putInt16Be(offset = 4, value = 1_000)
            putUInt16Be(offset = 8, value = 12_500)
        }

        val telemetry = requireNotNull(engine.consume(frame))
        assertTrue(telemetry.isLegacy)
        assertEquals(12_500.0, telemetry.tripDistanceMeters, 0.0001)
    }

    @Test
    fun legacyBegodeTotalDistanceReadsTheOdometerNotThePowerOffTimer() {
        val engine = BegodeProtocolEngine()
        val status = begodeFrame(type = 4, page = 24).apply {
            putUInt16Be(offset = 2, value = 109) // 7_200_000 m odometer, high word
            putUInt16Be(offset = 4, value = 56_576) // low word
            putUInt16Be(offset = 8, value = 7_200) // auto power-off countdown, not distance
            putUInt16Be(offset = 10, value = 60) // tiltback speed, not battery percent
        }

        val telemetry = requireNotNull(engine.consume(status))
        assertTrue(telemetry.isLegacy)
        assertEquals(7_200.0, telemetry.totalDistanceKm, 0.0001)
    }

    @Test
    fun legacyBegodeExposesTheWheelsOwnAlarmFlags() {
        val engine = BegodeProtocolEngine()
        val status = begodeFrame(type = 4, page = 24).apply {
            this[14] = (BegodeWheelAlert.HighPower or BegodeWheelAlert.OverTemperature).toByte()
        }

        val telemetry = requireNotNull(engine.consume(status))
        assertEquals(
            BegodeWheelAlert.HighPower or BegodeWheelAlert.OverTemperature,
            telemetry.wheelAlertFlags,
        )
    }

    @Test
    fun legacyBegodeHighPowerAlarmRecalibratesPwmToTheWheelsOwnThreshold() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        // 55.0 km/h at 60.00 V * 1.5 class scale = 90.0 V, no phase current.
        val moving = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_000)
            putInt16Be(offset = 4, value = 1_528)
        }
        engine.consume(moving)
        val pwmBefore = requireNotNull(engine.consume(moving)).pwmPercent

        val alarm = begodeFrame(type = 4, page = 24).apply {
            this[14] = BegodeWheelAlert.HighPower.toByte()
        }
        engine.consume(alarm)
        val telemetry = requireNotNull(engine.consume(moving))

        // The wheel raised its 80% alarm at this speed, so the estimate has to agree with it.
        assertTrue(pwmBefore < 70.0, "expected the class default to under-read here, got $pwmBefore")
        assertEquals(80.0 * 1.01, telemetry.pwmPercent, 0.1)
    }

    @Test
    fun legacyBegodeIgnoresHighPowerAlarmThatWouldImplyAnAbsurdCalibration() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        // 5.4 km/h: the wheel is alarming about something other than PWM saturation, and taking
        // the reading would leave the estimate reporting ~10x the real duty for the rest of the ride.
        val crawling = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_000)
            putInt16Be(offset = 4, value = 150)
        }
        engine.consume(crawling)
        val ratioBefore = engine.legacyPwmNoLoadRatio()

        val alarm = begodeFrame(type = 4, page = 24).apply {
            this[14] = BegodeWheelAlert.HighPower.toByte()
        }
        engine.consume(alarm)

        assertEquals(ratioBefore, engine.legacyPwmNoLoadRatio(), 0.0001)
    }

    @Test
    fun legacyBegodeRaisesTheNoLoadRatioTheWheelIsProvenToExceed() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        // 100.8 km/h at 66.00 V * 1.5 = 99.0 V, drawing 8.2 A: a free spin no default can explain.
        val freeSpin = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_600)
            putInt16Be(offset = 4, value = 2_800)
            putInt16Be(offset = 10, value = 820)
        }

        val ratioBefore = engine.legacyPwmNoLoadRatio()
        repeat(3) { engine.consume(freeSpin) }

        // 100.8 / (99.0 * 0.9): the ratio at which the reading is exactly the 100% ceiling.
        assertEquals(1.1313, engine.legacyPwmNoLoadRatio(), 0.001)
        assertTrue(engine.legacyPwmNoLoadRatio() > ratioBefore)
    }

    @Test
    fun legacyBegodeKeepsOneCorruptSpeedSampleFromMovingTheCalibration() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        val ratioBefore = engine.legacyPwmNoLoadRatio()
        val spike = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_600)
            putInt16Be(offset = 4, value = 2_800)
        }
        val cruising = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_600)
            putInt16Be(offset = 4, value = 1_000)
        }

        engine.consume(spike)
        engine.consume(cruising)
        engine.consume(spike)

        assertEquals(ratioBefore, engine.legacyPwmNoLoadRatio(), 0.0001)
    }

    @Test
    fun modernBegodeTripDistanceUsesRawCounterDirectlyAsMeters() {
        val engine = BegodeProtocolEngine()
        val frame = begodeFrame(type = 0, page = 24).apply {
            putInt16Be(offset = 4, value = 1_000)
            putUInt16Be(offset = 8, value = 12_500) // raw counter is already meters for modern streams
        }

        val telemetry = requireNotNull(engine.consume(frame))
        assertFalse(telemetry.isLegacy)
        assertEquals(12_500.0, telemetry.tripDistanceMeters, 0.0001)
    }

    @Test
    fun legacyBegode100VoltClassBatteryPercentStaysOn24SBelow90Volts() {
        val engine = BegodeProtocolEngine().apply {
            setLegacyVoltageClassMaxVoltage(100.8)
        }

        // 92.5V -> 92.5/24 = 3.854V/cell -> 70.44%
        val frame92 = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 6_167) // 61.67 * 1.5 = 92.5V5
            putInt16Be(offset = 4, value = 100)
        }
        val t92 = requireNotNull(engine.consume(frame92))
        assertEquals(70.4375, t92.batteryPercent, 0.0001)

        // 88.5V -> 88.5/24 = 3.687V/cell -> 48.44% (would be 100% if series jumped to 20S)
        val frame88 = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 5_900) // 59.00 * 1.5 = 88.5V
            putInt16Be(offset = 4, value = 100)
        }
        val t88 = requireNotNull(engine.consume(frame88))
        assertEquals(48.4375, t88.batteryPercent, 0.0001)

        // 84.5V -> 84.5/24 = 3.521V/cell -> 27.13%
        val frame84 = begodeFrame(type = 0, page = 24).apply {
            putUInt16Be(offset = 2, value = 5_634) // 56.34 * 1.5 = 84.51V
            putInt16Be(offset = 4, value = 100)
        }
        val t84 = requireNotNull(engine.consume(frame84))
        assertEquals(27.125, t84.batteryPercent, 0.0001)
    }

    /**
     * Charge is read off the discharge curve with interpolation rather than in steps of 10%.
     * On an older Begode the percentage is derived from voltage, and a 10% step means half a
     * typical ride with nothing changing on screen.
     */
    @Test
    fun batteryPercentInterpolatesBetweenCurvePointsInsteadOfSteppingByTen() {
        val engine = BegodeProtocolEngine()

        // Curve points return exactly their tabulated values.
        assertEquals(50.0, engine.batteryPercentFromCellVoltage(3.70), 0.0001)
        assertEquals(60.0, engine.batteryPercentFromCellVoltage(3.78), 0.0001)
        // Halfway between two points is halfway between their percentages.
        assertEquals(55.0, engine.batteryPercentFromCellVoltage(3.74), 0.0001)

        // 10 mV has to move the number: this whole range used to be a single step.
        val low = engine.batteryPercentFromCellVoltage(3.71)
        val high = engine.batteryPercentFromCellVoltage(3.72)
        assertTrue(high > low, "$high is not above $low")
        assertTrue(high - low < 2.0, "step is too coarse: ${high - low}")

        // The ends of the curve clamp.
        assertEquals(100.0, engine.batteryPercentFromCellVoltage(4.30), 0.0001)
        assertEquals(0.0, engine.batteryPercentFromCellVoltage(2.50), 0.0001)
        // Below 3.35V no longer collapses to zero: a cell at 3.2V is not empty.
        assertTrue(engine.batteryPercentFromCellVoltage(3.20) > 0.0)
    }


    // The three frames below are lifted verbatim from capture
    // `385CFBC9BF07/20260727_084903`, twenty thousand frames of a real ride. Every moving sample
    // of that ride carries a negative speed word, so these also pin down that the sign is read
    // against the direction of travel and not on its own.

    /** Speed -20.23 km/h, current word -22.80 A: the two agree, so the pack is driving. */
    private val legacyDriving = byteArrayOf(
        0x55, 0xAA.toByte(), 0x17, 0xC5.toByte(), 0xFD.toByte(), 0xCE.toByte(), 0x00, 0x55,
        0x04, 0xE1.toByte(), 0xF7.toByte(), 0x18, 0xF9.toByte(), 0xB2.toByte(), 0x10, 0x89.toByte(),
        0x00, 0x08, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
    )

    /** Speed -22.93 km/h, current word +8.20 A: they disagree, so the motor is braking. */
    private val legacyBraking = byteArrayOf(
        0x55, 0xAA.toByte(), 0x17, 0xCF.toByte(), 0xFD.toByte(), 0x83.toByte(), 0x00, 0x56,
        0x04, 0xFE.toByte(), 0x03, 0x34, 0xF9.toByte(), 0xAD.toByte(), 0x10, 0x89.toByte(),
        0x00, 0x08, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
    )

    /** Standstill. No direction to agree with, and a balancing wheel still draws. */
    private val legacyStationary = byteArrayOf(
        0x55, 0xAA.toByte(), 0x17, 0xB1.toByte(), 0x00, 0x00, 0x00, 0x54,
        0x1B, 0xCE.toByte(), 0x06, 0xB8.toByte(), 0xFA.toByte(), 0x70, 0x10, 0x89.toByte(),
        0x00, 0x08, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
    )

    @Test
    fun legacyBegodeDrivingReportsPowerLeavingThePack() {
        val telemetry = requireNotNull(BegodeProtocolEngine().consume(legacyDriving))

        assertTrue(telemetry.isLegacy)
        assertTrue(telemetry.power > 0.0, "driving must draw, was ${telemetry.power}")
        assertTrue(telemetry.current > 0.0, "driving must draw, was ${telemetry.current}")
    }

    @Test
    fun legacyBegodeBrakingReportsPowerReturningToThePack() {
        val telemetry = requireNotNull(BegodeProtocolEngine().consume(legacyBraking))

        assertTrue(telemetry.power < 0.0, "braking must return, was ${telemetry.power}")
        assertTrue(telemetry.current < 0.0, "braking must return, was ${telemetry.current}")
    }

    @Test
    fun legacyBegodeKeepsPhaseCurrentAndApparentPowerAsMagnitudes() {
        val telemetry = requireNotNull(BegodeProtocolEngine().consume(legacyBraking))

        assertTrue(telemetry.phaseCurrent > 0.0, "phase current is effort, not direction")
        assertTrue(telemetry.apparentPower > 0.0, "apparent power is a magnitude by definition")
        assertEquals(telemetry.apparentPower, -telemetry.power, 0.0001)
    }

    @Test
    fun legacyBegodeStandstillCountsAsDriving() {
        val telemetry = requireNotNull(BegodeProtocolEngine().consume(legacyStationary))

        assertEquals(0.0, telemetry.speedKmh, 0.0001)
        assertTrue(telemetry.power >= 0.0, "a balancing wheel draws, was ${telemetry.power}")
    }

    private fun begodeFrame(type: Int, page: Int): ByteArray {
        return ByteArray(24).apply {
            this[0] = 0x55
            this[1] = 0xAA.toByte()
            this[18] = type.toByte()
            this[19] = page.toByte()
            this[20] = 0x5A
            this[21] = 0x5A
            this[22] = 0x5A
            this[23] = 0x5A
        }
    }

    private fun ByteArray.putInt16Be(offset: Int, value: Int) {
        val raw = value and 0xffff
        this[offset] = ((raw ushr 8) and 0xff).toByte()
        this[offset + 1] = (raw and 0xff).toByte()
    }

    private fun ByteArray.putUInt16Be(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }
}
