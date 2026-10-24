package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.sqrt

/**
 * Pulls the controller's battery current (`type=7 @2`) onto the level the smart BMS measures.
 *
 * ## Why the controller's number is not enough
 *
 * The controller measures the DC-bus current itself, and on some wheels it is off - in scale, in
 * zero, or both - while the BMS on the same pack measures it right:
 *
 * - An X-Way (`GotWay_012960`, 168 V, 3000 Wh on the plate) reads 4.39 A on average over a
 *   6.5-minute ride. Its two BMS read 3.0 A each over the same ride, 6.0 A together - 1.37 times
 *   more. Integrated from the controller alone, the pack-energy model learned 1983 Wh for that
 *   wheel; times 1.32 that is about 2620 Wh, which is what a 3000 Wh plate leaves usable.
 * - A RACE (`GotWay_011414`) reads -2.8 A standing still for a whole ride, and every stop of it
 *   integrated as charging: -130 Wh over 10 km, against about 285 Wh that the cell voltages say
 *   the pack gave.
 * - A Master (`GotWay_006977`) reads 2.91 A against its BMS pair's 3.07 A: the controller is
 *   right there, and the correction leaves it within a few per cent of where it was.
 *
 * ## How
 *
 * The BMS current is slow - each of the four `type=1` pages refreshes about once a second and
 * trails the controller by about four seconds - and coarse, 0.1 A. The controller's is fast. So
 * the output keeps the controller's fast part and takes the level from the BMS: both are
 * smoothed over about [SMOOTHING_FRAMES] battery-current frames, and the difference of the two
 * averages is added to every controller reading. Over a ride the corrected current integrates
 * to what the BMS integrates to; a transient only moves as the controller saw it.
 *
 * Pages 0 and 1 are the first BMS and 2 and 3 the second, as WheelLog's `GotwayAdapter` splits
 * them; two pages of one BMS are averaged and the BMS added. On the X-Way that reading is the only
 * one that fits the plate: taking the four pages as four BMS doubles the pack to 5400 Wh, and
 * taking one halves it to 1450.
 *
 * ## The BMS sign is learned
 *
 * The BMS does not agree with itself across wheels: a Master's BMS reads a draw negative, an
 * X-Way's positive. The sign is taken from how the two currents move together: window means of
 * the controller are paired with the BMS means one window later, to cover the lag, and once
 * there are enough windows with enough spread the sign of their correlation is the BMS sign.
 * On the Master and the X-Way this settles within 90..130 frames, about half a minute of riding.
 * Until then - and for a whole session spent parked, where nothing moves - the controller's
 * number is published as it is.
 *
 * A BMS that goes quiet for [STALE_FRAMES] frames drops the correction until it speaks again.
 */
internal class BegodeBatteryCurrentCalibration {
    private val bmsPages = arrayOfNulls<Double>(BMS_PAGE_COUNT)
    private var framesSinceBms = Int.MAX_VALUE

    private var smoothedController = Double.NaN
    private var smoothedBms = Double.NaN

    private var bmsSign = 0
    private var windowController = 0.0
    private var windowBms = 0.0
    private var windowFrames = 0
    private var previousWindowController = Double.NaN
    private var pairs = 0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumXX = 0.0
    private var sumYY = 0.0
    private var sumXY = 0.0

    fun reset() {
        bmsPages.fill(null)
        framesSinceBms = Int.MAX_VALUE
        smoothedController = Double.NaN
        smoothedBms = Double.NaN
        bmsSign = 0
        windowController = 0.0
        windowBms = 0.0
        windowFrames = 0
        previousWindowController = Double.NaN
        pairs = 0
        sumX = 0.0
        sumY = 0.0
        sumXX = 0.0
        sumYY = 0.0
        sumXY = 0.0
    }

    /** One BMS current reading, `type=1` page [page], in amperes as the BMS signs it. */
    fun onBmsCurrent(page: Int, amps: Double) {
        if (page !in 0 until BMS_PAGE_COUNT || !amps.isFinite()) return
        bmsPages[page] = amps
        framesSinceBms = 0
    }

    /** The BMS sign once it is known: `1` or `-1`, or `0` while it is not. */
    fun bmsSign(): Int = bmsSign

    /**
     * The controller's reading [controllerAmps] (positive while the pack is spent), corrected
     * onto the BMS level once the BMS sign is known.
     */
    fun correct(controllerAmps: Double): Double {
        if (!controllerAmps.isFinite()) return controllerAmps
        if (framesSinceBms != Int.MAX_VALUE) framesSinceBms++
        val bms = bmsTotal()
        if (bms == null || framesSinceBms > STALE_FRAMES) return controllerAmps

        smoothedController = smooth(smoothedController, controllerAmps)
        smoothedBms = smooth(smoothedBms, bms)
        learnSign(controllerAmps, bms)

        return if (bmsSign == 0) {
            controllerAmps
        } else {
            controllerAmps + (bmsSign * smoothedBms - smoothedController)
        }
    }

    private fun bmsTotal(): Double? {
        var total = 0.0
        var any = false
        for (first in 0 until BMS_PAGE_COUNT step PAGES_PER_BMS) {
            var sum = 0.0
            var count = 0
            for (page in first until first + PAGES_PER_BMS) {
                bmsPages[page]?.let {
                    sum += it
                    count++
                }
            }
            if (count > 0) {
                total += sum / count
                any = true
            }
        }
        return if (any) total else null
    }

    private fun smooth(previous: Double, sample: Double): Double =
        if (previous.isNaN()) sample else previous + (sample - previous) / SMOOTHING_FRAMES

    private fun learnSign(controllerAmps: Double, bms: Double) {
        if (bmsSign != 0) return
        windowController += controllerAmps
        windowBms += bms
        windowFrames++
        if (windowFrames < SIGN_WINDOW_FRAMES) return

        val x = previousWindowController
        val y = windowBms / windowFrames
        previousWindowController = windowController / windowFrames
        windowController = 0.0
        windowBms = 0.0
        windowFrames = 0
        if (x.isNaN()) return

        pairs++
        sumX += x
        sumY += y
        sumXX += x * x
        sumYY += y * y
        sumXY += x * y
        if (pairs < SIGN_MIN_WINDOWS) return
        val n = pairs.toDouble()
        val varianceX = sumXX / n - (sumX / n) * (sumX / n)
        val varianceY = sumYY / n - (sumY / n) * (sumY / n)
        if (varianceX < SIGN_MIN_CONTROLLER_VARIANCE || varianceY <= 0.0) return
        val covariance = sumXY / n - (sumX / n) * (sumY / n)
        val correlation = covariance / sqrt(varianceX * varianceY)
        if (correlation >= SIGN_MIN_CORRELATION) bmsSign = 1
        if (correlation <= -SIGN_MIN_CORRELATION) bmsSign = -1
    }

    private companion object {
        const val BMS_PAGE_COUNT = 4
        const val PAGES_PER_BMS = 2

        /** Battery-current frames the two levels are averaged over; about 30 s on a wheel. */
        const val SMOOTHING_FRAMES = 100.0

        /** Battery-current frames without a BMS reading after which the BMS counts as gone. */
        const val STALE_FRAMES = 50

        const val SIGN_WINDOW_FRAMES = 10
        const val SIGN_MIN_WINDOWS = 8

        /** Spread of the controller's window means, A^2, before a correlation means anything. */
        const val SIGN_MIN_CONTROLLER_VARIANCE = 0.5
        const val SIGN_MIN_CORRELATION = 0.5
    }
}
