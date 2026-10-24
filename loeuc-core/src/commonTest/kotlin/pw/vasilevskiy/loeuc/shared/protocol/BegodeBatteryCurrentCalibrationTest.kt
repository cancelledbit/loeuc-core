package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BegodeBatteryCurrentCalibrationTest {
    /**
     * An X-Way: the controller reads 1/1.35 of the real current, and its BMS read a draw
     * positive. After the sign has settled the corrected current averages to the real one.
     */
    @Test
    fun aControllerThatReadsLowIsPulledUpToTheBms() {
        val ride = SimulatedRide(scale = 1.35, offset = 0.0, bmsSign = 1)

        ride.run(frames = 900)

        assertEquals(1, ride.calibration.bmsSign())
        assertEquals(ride.meanTrue, ride.meanCorrected, ride.meanTrue * 0.05)
        assertTrue(ride.meanController < ride.meanTrue * 0.8, "the controller alone is low")
    }

    /** A Master: the BMS read a draw negative, and the controller is already right. */
    @Test
    fun aNegativeBmsSignIsLearnedAndARightControllerStaysRight() {
        val ride = SimulatedRide(scale = 1.0, offset = 0.0, bmsSign = -1)

        ride.run(frames = 900)

        assertEquals(-1, ride.calibration.bmsSign())
        assertEquals(ride.meanTrue, ride.meanCorrected, ride.meanTrue * 0.05)
    }

    /** A RACE: the controller's zero sits 2.8 A below the real one, so every stop charges. */
    @Test
    fun aControllerZeroThatHasDriftedIsPutBack() {
        val ride = SimulatedRide(scale = 1.0, offset = -2.8, bmsSign = 1)

        ride.run(frames = 900)
        // Parked: the wheel draws 0.3 A, the controller says -2.5 A.
        val parked = (0 until 300).map { ride.step(trueAmps = 0.3) }.last()

        assertEquals(0.3, parked, 0.2)
    }

    @Test
    fun aSessionSpentParkedLeavesTheControllerAsItIs() {
        val calibration = BegodeBatteryCurrentCalibration()

        val out = (0 until 600).map { frame ->
            calibration.onBmsCurrent(frame % 4, 0.4)
            calibration.correct(-1.0)
        }

        assertEquals(0, calibration.bmsSign())
        assertTrue(out.all { it == -1.0 })
    }

    @Test
    fun aWheelWithoutBmsCurrentKeepsTheControllerReading() {
        val calibration = BegodeBatteryCurrentCalibration()

        assertEquals(4.2, calibration.correct(4.2), 0.0)
    }

    @Test
    fun aBmsThatGoesQuietDropsTheCorrection() {
        val ride = SimulatedRide(scale = 1.35, offset = 0.0, bmsSign = 1)
        ride.run(frames = 900)

        val quiet = (0 until 60).map { ride.calibration.correct(5.0) }.last()

        assertEquals(5.0, quiet, 0.0)
    }

    /**
     * Frames through the engine: a `type=1` page carries the BMS current at `@8` in tenths of an
     * ampere, and the `type=7` current that follows comes out corrected.
     */
    @Test
    fun theEngineCorrectsTheBatteryCurrentFromTheBmsPages() {
        val engine = BegodeProtocolEngine()
        var last: BegodeTelemetry? = null
        val trueHistory = ArrayDeque<Double>()
        repeat(900) { frame ->
            val trueAmps = load(frame)
            trueHistory.addLast(trueAmps)
            val lagged = if (trueHistory.size > BMS_LAG_FRAMES) trueHistory.removeFirst() else trueHistory.first()
            engine.consume(
                frame(type = 1, page = frame % 4).apply {
                    putWord(6, 1_600)
                    putWord(8, (lagged / 2 * 10).toInt())
                },
            )
            last = engine.consume(frame(type = 7, page = 24).apply { putWord(2, -(trueAmps / 1.35 * 100).toInt()) })
        }

        val telemetry = requireNotNull(last)
        // The controller alone would say 4.3 A here.
        assertEquals(load(899), telemetry.current, 0.6)
        assertEquals(telemetry.voltage * telemetry.current, telemetry.power, 0.001)
    }

    /** A wheel riding a varying load, its controller and its two BMS reporting on it. */
    private class SimulatedRide(val scale: Double, val offset: Double, val bmsSign: Int) {
        val calibration = BegodeBatteryCurrentCalibration()
        private val history = ArrayDeque<Double>()
        private var frame = 0
        private var trueSum = 0.0
        private var controllerSum = 0.0
        private var correctedSum = 0.0
        private var counted = 0

        val meanTrue get() = trueSum / counted
        val meanController get() = controllerSum / counted
        val meanCorrected get() = correctedSum / counted

        fun run(frames: Int) {
            repeat(frames) {
                val trueAmps = load(frame)
                val corrected = step(trueAmps)
                // Scored once the sign has had time to settle and the averages to fill.
                if (frame >= frames / 2) {
                    trueSum += trueAmps
                    controllerSum += trueAmps / scale + offset
                    correctedSum += corrected
                    counted++
                }
            }
        }

        fun step(trueAmps: Double): Double {
            history.addLast(trueAmps)
            val lagged = if (history.size > BMS_LAG_FRAMES) history.removeFirst() else history.first()
            // Two BMS share the pack; each reads half, 0.1 A resolution, one page per frame.
            val perBms = (bmsSign * lagged / 2 * 10).toInt() / 10.0
            calibration.onBmsCurrent(frame % 4, perBms)
            frame++
            return calibration.correct(trueAmps / scale + offset)
        }
    }

    private fun frame(type: Int, page: Int): ByteArray = ByteArray(24).apply {
        this[0] = 0x55
        this[1] = 0xAA.toByte()
        this[18] = type.toByte()
        this[19] = page.toByte()
        this[20] = 0x5A
        this[21] = 0x5A
        this[22] = 0x5A
        this[23] = 0x5A
    }

    private fun ByteArray.putWord(offset: Int, value: Int) {
        val raw = value and 0xffff
        this[offset] = ((raw ushr 8) and 0xff).toByte()
        this[offset + 1] = (raw and 0xff).toByte()
    }

    private companion object {
        /** The BMS trails the controller by about four seconds, twelve frames at 3 Hz. */
        const val BMS_LAG_FRAMES = 12

        /** Real pack current: 6 A on average, swinging by 5 A every minute or so. */
        fun load(frame: Int): Double = 6.0 + 5.0 * sin(2 * PI * frame / 180.0) +
            if (frame % 37 < 3) 8.0 else 0.0
    }
}
