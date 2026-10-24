package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertCadence
import pw.vasilevskiy.loeuc.shared.alerts.model.AccelerationCurve
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertCadenceTest {

    private val pwmLike = AlertType.Accelerating(
        minIntervalMs = 50L,
        maxIntervalMs = 450L,
        curve = AccelerationCurve.LINEAR,
        pitchRiseRatio = 2.33,
        continuousFromRatio = 0.95,
    )

    @Test
    fun testAtThresholdIntervalIsWidestAndPitchUnshifted() {
        val point = AlertCadence.forProgress(pwmLike, 0.0)

        assertEquals(450L, point.intervalMs)
        assertEquals(1.0, point.pitchRatio)
        assertFalse(point.continuous)
    }

    @Test
    fun testAtTopPitchReachesRatioAndToneIsContinuous() {
        val point = AlertCadence.forProgress(pwmLike, 1.0)

        assertTrue(point.continuous)
        assertEquals(0L, point.intervalMs)
        assertEquals(2.33, point.pitchRatio)
    }

    @Test
    fun testToneBecomesContinuousExactlyAtConfiguredRatio() {
        assertFalse(AlertCadence.forProgress(pwmLike, 0.94).continuous)
        assertTrue(AlertCadence.forProgress(pwmLike, 0.95).continuous)
    }

    @Test
    fun testIntervalShrinksAndPitchRisesMonotonically() {
        for (curve in AccelerationCurve.entries) {
            val type = pwmLike.copy(curve = curve)
            val start = AlertCadence.forProgress(type, 0.0)
            var previousInterval = start.intervalMs
            var previousPitch = start.pitchRatio

            for (step in 0..19) {
                val point = AlertCadence.forProgress(type, step / 20.0)
                assertTrue(point.intervalMs <= previousInterval, "interval grew at progress ${step / 20.0}, curve=$curve")
                assertTrue(point.pitchRatio >= previousPitch, "pitch fell at progress ${step / 20.0}, curve=$curve")
                previousInterval = point.intervalMs
                previousPitch = point.pitchRatio
            }
        }
    }

    @Test
    fun testPitchEndpointsHoldForAllCurveShapes() {
        // pitchRiseRatio = 2.33, not 1.0, or the assertion would pass for any curve at all.
        for (curve in AccelerationCurve.entries) {
            val type = pwmLike.copy(curve = curve)
            assertEquals(1.0, AlertCadence.forProgress(type, 0.0).pitchRatio, "curve=$curve")
            assertEquals(type.pitchRiseRatio, AlertCadence.forProgress(type, 1.0).pitchRatio, "curve=$curve")
        }
    }

    @Test
    fun testIntervalEndpointsHoldForAllCurveShapes() {
        for (curve in AccelerationCurve.entries) {
            val type = pwmLike.copy(curve = curve)
            assertEquals(type.maxIntervalMs, AlertCadence.forProgress(type, 0.0).intervalMs, "curve=$curve")
            assertTrue(AlertCadence.forProgress(type, 1.0).continuous, "curve=$curve")
        }
    }

    @Test
    fun testLastBeepsBeforeTheToneConvergeOnMinIntervalWhateverTheStartInterval() {
        // The regression: the ramp ran over the full 0..1 and was cut off at the continuous
        // point, so the last beeps sat at low + 0.1 * span - 265 ms from a 2 s start, 485 ms
        // from a 4 s start. The start interval must only shape the start and the middle.
        for (curve in AccelerationCurve.entries) {
            val endIntervals = listOf(500L, 2000L, 4000L).map { start ->
                val type = AlertType.Accelerating(
                    minIntervalMs = 50L,
                    maxIntervalMs = start,
                    curve = curve,
                    continuousFromRatio = 0.9,
                )
                val justBefore = AlertCadence.forProgress(type, 0.8999)
                assertFalse(justBefore.continuous, "curve=$curve start=$start")
                justBefore.intervalMs
            }
            for (interval in endIntervals) {
                assertTrue(interval in 50L..55L, "end interval $interval, curve=$curve, all=$endIntervals")
            }
        }
    }

    @Test
    fun testStartIntervalStillShapesTheBeginningOfTheRamp() {
        val slow = pwmLike.copy(maxIntervalMs = 4000L)
        val fast = pwmLike.copy(maxIntervalMs = 2000L)

        assertEquals(4000L, AlertCadence.forProgress(slow, 0.0).intervalMs)
        assertEquals(2000L, AlertCadence.forProgress(fast, 0.0).intervalMs)
        assertTrue(AlertCadence.forProgress(slow, 0.3).intervalMs > AlertCadence.forProgress(fast, 0.3).intervalMs)
    }

    @Test
    fun testIntervalIsInterpolatedOverTheBeepingPartOfTheRamp() {
        // Linear, 50..450 ms, continuous from 0.95: halfway to the merge is halfway down.
        val interval = AlertCadence.forProgress(pwmLike, 0.475).intervalMs
        assertTrue(interval in 249L..250L, "interval=$interval")
    }

    @Test
    fun testContinuousPointAtOrBeyondTheEdgesDoesNotBreakTheRamp() {
        val neverMerges = pwmLike.copy(continuousFromRatio = 1.5)
        assertEquals(450L, AlertCadence.forProgress(neverMerges, 0.0).intervalMs)
        assertEquals(250L, AlertCadence.forProgress(neverMerges, 0.5).intervalMs)
        assertEquals(50L, AlertCadence.forProgress(neverMerges, 1.0).intervalMs)
        assertFalse(AlertCadence.forProgress(neverMerges, 1.0).continuous)

        val mergesAtTheTop = pwmLike.copy(continuousFromRatio = 1.0)
        assertEquals(250L, AlertCadence.forProgress(mergesAtTheTop, 0.5).intervalMs)
        assertTrue(AlertCadence.forProgress(mergesAtTheTop, 1.0).continuous)

        for (ratio in listOf(0.0, -1.0)) {
            val alwaysContinuous = pwmLike.copy(continuousFromRatio = ratio)
            val point = AlertCadence.forProgress(alwaysContinuous, 0.0)
            assertTrue(point.continuous, "ratio=$ratio")
            assertEquals(0L, point.intervalMs, "ratio=$ratio")
        }

        val notANumber = pwmLike.copy(continuousFromRatio = Double.NaN)
        val point = AlertCadence.forProgress(notANumber, 0.5)
        assertEquals(250L, point.intervalMs)
    }

    @Test
    fun testProgressIsClampedOutsideThresholds() {
        assertEquals(AlertCadence.forProgress(pwmLike, 0.0), AlertCadence.forProgress(pwmLike, -5.0))
        assertEquals(AlertCadence.forProgress(pwmLike, 1.0), AlertCadence.forProgress(pwmLike, 42.0))
    }

    @Test
    fun testPitchStaysFlatWhenRiseIsDisabled() {
        val noRise = pwmLike.copy(pitchRiseRatio = 1.0)

        assertEquals(1.0, AlertCadence.forProgress(noRise, 0.0).pitchRatio)
        assertEquals(1.0, AlertCadence.forProgress(noRise, 1.0).pitchRatio)
    }
}
