package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BegodeProtocolEngineTest {
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
    fun modernBegodeBatteryPercentFallsBackToVoltageBucketsNotLegacyVoltageClass() {
        val engine = BegodeProtocolEngine().apply {
            // Simulates a device that never had its (unrelated legacy-only) voltage
            // class touched, still defaulting to 100.8V.
            setLegacyVoltageClassMaxVoltage(100.8)
        }
        val battery = begodeFrame(type = 1, page = 0).apply {
            putUInt16Be(offset = 6, value = 1_500) // 150.0V, no cell frames received yet
        }

        val telemetry = requireNotNull(engine.consume(battery))

        assertFalse(telemetry.isLegacy)
        assertEquals(150.0, telemetry.voltage, 0.0001)
        // 150V falls in the 145..180V bucket -> 40S -> 3.75V/cell -> 56.25% on the discharge
        // curve (3.70V = 50%, 3.78V = 60%, interpolated in between).
        // If the legacy voltage class (100.8V -> 24S) leaked into this modern
        // fallback, cellVoltage would be 150/24 = 6.25V, clamping to 100%.
        assertEquals(56.25, telemetry.batteryPercent, 0.0001)
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
