package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // The wire word is -250: a draw, which the engine publishes as a positive current.
        assertEquals(2.5, telemetry.current, 0.0001)
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

        // -478 on the wire while the wheel pulls away: the pack is driving, so the current is positive.
        assertEquals(4.78, telemetry.current, 0.0001)
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

    /**
     * "Off" and "has not said yet" are different answers, and the frame can only say the
     * first one: a speed of 100 km/h or more means the rider switched tilt-back off. NaN
     * stays reserved for a wheel that has sent no status frame at all - the settings screen
     * prints "off" for a zero and "unknown" for a NaN, and a preset captures only the former.
     */
    @Test
    fun aTiltbackSpeedNobodyRidesAtMeansOffRatherThanUnknown() {
        val engine = BegodeProtocolEngine()
        // Settings are only read by the modern parse, and an identification frame turns it on:
        // 100.8 V in the packet field means the stream is not the legacy one.
        engine.consume(
            begodeFrame(type = 1, page = 0).apply {
                putUInt16Be(offset = 6, value = 1_008)
            },
        )
        val status = begodeFrame(type = 4, page = 24).apply {
            putUInt16Be(offset = 10, value = 100)
        }

        val telemetry = requireNotNull(engine.consume(status))
        assertTrue(!telemetry.isLegacy, "the frame has to be parsed the modern way")
        assertEquals(0.0, telemetry.tiltbackSpeedKmh, 0.0001)
        assertTrue(BegodeTelemetry().tiltbackSpeedKmh.isNaN())
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
    fun legacyBegodeKeepsPhaseCurrentMagnitudeAndSignedApparentPower() {
        val telemetry = requireNotNull(BegodeProtocolEngine().consume(legacyBraking))

        assertTrue(telemetry.phaseCurrent > 0.0, "phase current is effort, not direction")
        assertTrue(telemetry.apparentPower < 0.0, "legacy VA must show regeneration")
        assertEquals(telemetry.power, telemetry.apparentPower, 0.0001)
    }

    @Test
    fun legacyBegodeStandstillCountsAsDriving() {
        val telemetry = requireNotNull(BegodeProtocolEngine().consume(legacyStationary))

        assertEquals(0.0, telemetry.speedKmh, 0.0001)
        assertTrue(telemetry.power >= 0.0, "a balancing wheel draws, was ${telemetry.power}")
    }

    /**
     * Battery current and power on a modern Begode are positive while the pack drives the motor.
     *
     * Real frames from capture `385CFBC9BF07/20260727_084903` (Begode Master, stock firmware),
     * the strongest draw of the ride: `type=7 @2` reads -22.83 A at 26 km/h, with a phase current
     * that agrees with the speed. Published as read, a whole ride of these integrated to a
     * negative consumption.
     */
    @Test
    fun modernBegodeHardDrawReportsPowerLeavingThePack() {
        val telemetry = consumeAll(BegodeProtocolEngine(), modernDrawBattery, modernDrawMain, modernDrawPower)

        assertFalse(telemetry.isLegacy)
        assertEquals(26.28, telemetry.speedKmh, 0.0001)
        assertEquals(120.9, telemetry.voltage, 0.0001)
        assertEquals(22.83, telemetry.current, 0.0001)
        assertEquals(120.9 * 22.83, telemetry.power, 0.01)
        assertEquals(120.9 * 22.83, telemetry.apparentPower, 0.01)
    }

    /** The strongest return of the same ride: `@2` reads +7.53 A at 30 km/h, phase current against the speed. */
    @Test
    fun modernBegodeBrakingReportsPowerReturningToThePack() {
        val telemetry = consumeAll(BegodeProtocolEngine(), modernReturnBattery, modernReturnMain, modernReturnPower)

        assertEquals(-7.53, telemetry.current, 0.0001)
        assertEquals(-122.5 * 7.53, telemetry.power, 0.01)
        assertEquals(122.5 * 7.53, telemetry.apparentPower, 0.01, "apparent power is a magnitude by definition")
    }

    /**
     * The flip agrees with the phase-current convention established on its own evidence: the same
     * real main frame decoded on its own, through the legacy path, gives the same power direction
     * as the modern battery current that arrived with it.
     */
    @Test
    fun modernBatteryCurrentAgreesWithThePhaseCurrentConventionOnTheSameFrames() {
        val draw = consumeAll(BegodeProtocolEngine(), modernDrawBattery, modernDrawMain, modernDrawPower)
        val drawPhase = requireNotNull(BegodeProtocolEngine().consume(modernDrawMain))
        val back = consumeAll(BegodeProtocolEngine(), modernReturnBattery, modernReturnMain, modernReturnPower)
        val backPhase = requireNotNull(BegodeProtocolEngine().consume(modernReturnMain))

        assertTrue(drawPhase.isLegacy && backPhase.isLegacy)
        assertTrue(drawPhase.power > 0.0 && draw.power > 0.0, "draw: ${drawPhase.power} / ${draw.power}")
        assertTrue(backPhase.power < 0.0 && back.power < 0.0, "return: ${backPhase.power} / ${back.power}")
    }

    private val modernDrawBattery = hex("55 AA 00 5A 00 00 04 B9 FF F0 00 18 00 17 02 6A 7D 01 01 00 5A 5A 5A 5A")
    private val modernDrawMain = hex("55 AA 17 84 FD 26 00 50 0A 86 F2 40 F8 42 10 89 00 08 00 18 5A 5A 5A 5A")
    private val modernDrawPower = hex("55 AA F7 15 00 00 00 22 00 24 00 00 00 00 00 00 00 00 07 18 5A 5A 5A 5A")
    private val modernReturnBattery = hex("55 AA 00 5A 00 00 04 C9 FF D9 00 18 00 17 02 63 7D 01 01 00 5A 5A 5A 5A")
    private val modernReturnMain = hex("55 AA 17 F2 FC C6 00 59 06 4F 0A F0 F9 E2 10 89 00 08 00 18 5A 5A 5A 5A")
    private val modernReturnPower = hex("55 AA 02 F1 00 00 00 1E 00 17 00 00 00 00 00 00 00 00 07 18 5A 5A 5A 5A")

    // One frame of each type from a RACE capture (`7A1371_184024`), the one wheel in the dumps
    // whose settings fields are all away from zero. Its `type=0` voltage reads the legacy way,
    // and it sends no `type=1`, so `type=4` goes through the legacy status parse.
    private val raceLive = hex("55 AA 1A 26 00 00 00 AE 00 1E 00 AA 02 D4 10 C1 00 09 00 18 5A 5A 5A 5A")
    private val raceStatus = hex("55 AA 00 3A 24 C3 29 00 1C 20 00 C8 00 01 00 00 00 12 04 18 5A 5A 5A 5A")
    private val racePower = hex("55 AA 00 9A 00 62 00 1B 00 01 FF EE 00 14 00 00 00 00 07 18 5A 5A 5A 5A")

    /**
     * The settings a RACE reports, read where WheelHUD reads them. Every value here held for the
     * whole capture.
     */
    @Test
    fun theSettingsAWheelReportsAreReadBackFromItsFrames() {
        val telemetry = consumeAll(BegodeProtocolEngine(), racePower, raceLive, raceStatus)

        assertEquals(1.0, telemetry.rideMode, 0.0)
        assertEquals(0.0, telemetry.tiltbackSpeedKmh, 0.0, "200 km/h is the wheel saying off")
        assertEquals(false, telemetry.usesMiles)
        assertEquals(2, telemetry.speedAlarmMode)
        assertEquals(2, telemetry.sideTiltThreshold)
        assertEquals(1, telemetry.ledMode)
        assertEquals(6, telemetry.fieldWeakening)
        assertEquals(2, telemetry.sideTiltAngleIndex)
        assertEquals(4, telemetry.pedalDip)
        assertEquals(2, telemetry.pedalTrim)
        assertEquals(9, telemetry.buzzerVolume)
    }

    /**
     * `type=0 @14` and `@17` mean something else until `type=7` has shown the stream is the
     * modern one: an RS C30 without `type=7` reads volume 12 and a trim of -4 there.
     */
    @Test
    fun theLiveFrameSettingsWaitForTheStatusFrame() {
        val telemetry = consumeAll(BegodeProtocolEngine(), raceLive)

        assertNull(telemetry.pedalDip)
        assertNull(telemetry.pedalTrim)
        assertNull(telemetry.buzzerVolume)
    }

    /** On Freestyl3r firmware `type=0 @14` is the motor PWM, not the pedal settings. */
    @Test
    fun customFirmwarePwmIsNotReadAsPedalSettings() {
        val engine = BegodeProtocolEngine()
        engine.consume("CF2002001".encodeToByteArray())

        val telemetry = consumeAll(engine, racePower, raceLive)

        assertNull(telemetry.pedalDip)
        assertNull(telemetry.pedalTrim)
    }

    /** A value outside what the field can hold is no reading, not a clamped one. */
    @Test
    fun outOfRangeSettingFieldsStayUnknown() {
        val status = begodeFrame(type = 4, page = 24).apply {
            this[6] = 0x01 // side-fall level bits = 3
            this[7] = 0x80.toByte()
            this[13] = 0x0C // LED mode 12
        }
        val power = begodeFrame(type = 7, page = 24).apply { this[5] = 0xAB.toByte() }

        val telemetry = consumeAll(BegodeProtocolEngine(), status, power)

        assertNull(telemetry.sideTiltThreshold)
        assertNull(telemetry.ledMode)
        assertNull(telemetry.fieldWeakening)
        assertNull(telemetry.sideTiltAngleIndex)
    }

    private fun consumeAll(engine: BegodeProtocolEngine, vararg frames: ByteArray): BegodeTelemetry =
        frames.map { engine.consume(it) }.last().let(::requireNotNull)

    private fun hex(text: String): ByteArray =
        text.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /**
     * A wheel that does not fill `type=7` gets the estimate, not a zero.
     *
     * An EX30 sends the frame 1,666 times in a real capture and leaves everything past the
     * battery current at zero: no motor temperature, no PWM. Published as read, that zero
     * pins the overload gauge at 0 % for the whole ride, which reads as "no load" right up
     * to the cutout - and being recognised as a modern wheel had switched off the estimate
     * every Gotway otherwise runs on.
     */
    @Test
    fun aStubPowerFrameGetsTheEstimateInsteadOfAZero() {
        val engine = BegodeProtocolEngine()
        engine.setPackSeriesCells(32)
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 1_335)
        }
        // Exactly what the EX30 sends: battery current, and nothing else.
        val stubPower = begodeFrame(type = 7, page = 24).apply {
            putInt16Be(offset = 2, value = 57)
        }
        val moving = begodeFrame(type = 0, page = 24).apply {
            putInt16Be(offset = 4, value = 1_000)
            putInt16Be(offset = 10, value = 2_000)
        }

        engine.consume(battery)
        engine.consume(stubPower)
        val telemetry = requireNotNull(engine.consume(moving))

        assertFalse(telemetry.isLegacy, "the wheel stays on the modern parse")
        assertTrue(
            telemetry.motorTemperature.isNaN(),
            "a motor temperature of exactly 0.00 C is an unfilled field, not a reading, was ${telemetry.motorTemperature}",
        )
        assertTrue(
            telemetry.pwmPercent > 0.0,
            "the PWM estimate has to work where the wheel reports none of its own, was ${telemetry.pwmPercent}",
        )
    }

    /** A wheel that does fill the frame keeps its own number; the estimate stays out of it. */
    @Test
    fun aRealPowerFrameWinsOverTheEstimate() {
        val engine = BegodeProtocolEngine()
        engine.setPackSeriesCells(32)
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 1_335)
        }
        val power = begodeFrame(type = 7, page = 24).apply {
            putInt16Be(offset = 2, value = -250)
            putInt16Be(offset = 6, value = 45)
            putInt16Be(offset = 8, value = 37)
        }
        val moving = begodeFrame(type = 0, page = 24).apply {
            putInt16Be(offset = 4, value = 1_000)
            putInt16Be(offset = 10, value = 2_000)
        }

        engine.consume(battery)
        engine.consume(power)
        val telemetry = requireNotNull(engine.consume(moving))

        assertEquals(45.0, telemetry.motorTemperature, 0.0001)
        assertEquals(37.0, telemetry.pwmPercent, 0.0001)
    }

    /**
     * The catalogue sets the estimate's baseline, because it knows the model and the manual
     * voltage class only exists for wheels whose model is not known.
     *
     * The no-load speed is not proportional to the pack class - 102.7 km/h at 100.8 V against
     * 113.0 at 134.4 - so a 32S wheel left on the 100.8 V default reads about a fifth low,
     * which is the wrong direction to be wrong in for an overload warning.
     */
    @Test
    fun theCatalogueSetsThePwmEstimateBaseline() {
        val engine = BegodeProtocolEngine()

        engine.setLegacyVoltageClassMaxVoltage(100.8)
        assertEquals(102.7 / 100.8, engine.legacyPwmNoLoadRatio(), 0.0001)

        engine.setPackSeriesCells(32)
        assertEquals(113.0 / 134.4, engine.legacyPwmNoLoadRatio(), 0.0001)
    }

    /**
     * The power margin comes back from `type=1 @2`, as the complement of the load level.
     *
     * The notes could not decide what this field was - "likely SOC" in one place, "power alarm
     * threshold" in another. It is not the charge: an EX30 reports 85 while sitting at 133.5 V
     * of a 134.4 V pack, and a Commander holds 90 unchanged from 0 to 36 km/h. A number that
     * does not move while the wheel does is a setting, and EUC World reads the same field the
     * same way - range 50..100, published as `100 - value`.
     */
    @Test
    fun thePowerMarginComesBackFromTheBatteryFrame() {
        val engine = BegodeProtocolEngine()
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 2, value = 85)
            putUInt16Be(offset = 6, value = 1_335)
        }

        val telemetry = requireNotNull(engine.consume(battery))

        assertEquals(15.0, telemetry.powerMarginPercent, 0.0001)
    }

    /** Outside the window the vendor apps accept, the field is something else - or nothing. */
    @Test
    fun aPowerLevelOutsideTheAcceptedWindowIsIgnored() {
        val engine = BegodeProtocolEngine()
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 2, value = 7)
            putUInt16Be(offset = 6, value = 1_335)
        }

        val telemetry = requireNotNull(engine.consume(battery))

        assertTrue(telemetry.powerMarginPercent.isNaN())
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
