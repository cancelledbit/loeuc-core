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
