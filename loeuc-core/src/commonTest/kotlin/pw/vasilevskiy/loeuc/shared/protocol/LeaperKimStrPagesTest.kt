package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Page names come from the field sets of the twelve shapes seen in the 2026-08-11 captures, so the
 * fixtures here are those field lists verbatim.
 */
class LeaperKimStrPagesTest {
    private val mainPage = setOf(
        "IM", "Fs", "VQ", "VD", "Vqd", "Out", "Bem", "RA", "ChgAD", "CM", "CdA", "TC", "FTC",
        "LDpp", "FdHz", "dKM", "MdKM", "GC", "CC", "Lm1", "V", "Vpp", "Vnp", "Vmx", "Vmi", "Cmo",
        "Cmt", "Vp", "AA", "AB", "AC", "AP", "AQ", "AD", "AI",
    )
    private val temperaturePage = setOf(
        "Top", "Cnt", "TA", "FA", "IA", "LwC", "CTr", "VQ", "VD", "Vqd", "Cres", "Gres", "mWH",
        "Vgd", "Cmo", "AQ", "Asc", "Wht", "Wcm", "Wma", "Cci", "Cma", "OfC",
    )
    private val motorPage = setOf(
        "Ha", "Pole", "rAB", "di1", "di2", "Dif", "HPS", "Di", "JA", "IA", "FA", "AC", "BC", "CC",
        "ApS", "BpS", "CpS", "AD", "BD", "CD", "Dir", "SDr", "isH", "DS", "LDpp", "6Dpp", "HdHz",
    )
    private val imuPage = setOf(
        "MP", "AX", "AY", "AZ", "GX", "GY", "GZ", "GGY", "ASqt", "RSqt", "q0", "q1", "q2", "q3",
        "p", "r", "y", "Kp", "Kg", "Rtc", "LiC", "OmC",
    )
    private val cellsPage = (1..36).map { it.toString().padStart(2, '0') }.toSet() +
        setOf("Mx", "Mi", "BL")
    private val loopRatesPage = setOf(
        "HzF10", "HzF25", "HzF50", "HzPid", "HzF5K", "HzAdc", "HzWav", "dHzDTE", "U", "A", "B", "C",
    )

    @Test
    fun recognisesEveryPageSeenInTheCaptures() {
        assertEquals(LeaperKimStrPage.Main, LeaperKimStrPages.of(mainPage))
        assertEquals(LeaperKimStrPage.TemperatureAndPower, LeaperKimStrPages.of(temperaturePage))
        assertEquals(LeaperKimStrPage.MotorAndPhases, LeaperKimStrPages.of(motorPage))
        assertEquals(LeaperKimStrPage.Imu, LeaperKimStrPages.of(imuPage))
        assertEquals(LeaperKimStrPage.Cells, LeaperKimStrPages.of(cellsPage))
        assertEquals(LeaperKimStrPage.LoopRates, LeaperKimStrPages.of(loopRatesPage))
    }

    @Test
    fun theTemperaturePageIsNotMistakenForTheMainOne() {
        // Both carry VQ, VD, Vqd, AQ and Cmo. Only the main page carries dKM and only the
        // temperature page carries Cci, which is why those two are the markers.
        assertTrue("Cmo" in mainPage && "Cmo" in temperaturePage)
        assertEquals(LeaperKimStrPage.Main, LeaperKimStrPages.of(mainPage))
        assertEquals(LeaperKimStrPage.TemperatureAndPower, LeaperKimStrPages.of(temperaturePage))
    }

    @Test
    fun aLoneCellNumberIsNotEnoughToClaimTheCellsPage() {
        // `01` on its own is the kind of token a damaged record produces, so the cells page needs a
        // cell number and the highest-cell field together.
        assertEquals(LeaperKimStrPage.Unknown, LeaperKimStrPages.of(setOf("01", "02")))
        assertEquals(LeaperKimStrPage.Cells, LeaperKimStrPages.of(setOf("01", "Mx", "Mi")))
    }

    @Test
    fun anUnfamiliarFieldSetIsUnknownRatherThanGuessed() {
        assertEquals(LeaperKimStrPage.Unknown, LeaperKimStrPages.of(setOf("Zzz", "Qqq")))
        assertEquals(LeaperKimStrPage.Unknown, LeaperKimStrPages.of(emptySet()))
    }

    @Test
    fun theCarouselDwellsOnWantedPagesAndPassesThroughTheRest() {
        val plan = LeaperKimStrPagePlan.Carousel(
            pages = setOf(LeaperKimStrPage.Main, LeaperKimStrPage.TemperatureAndPower),
            dwellMillis = 8_000,
            skipMillis = 700,
        )

        assertFalse(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = 7_999))
        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = 8_000))
        // A page nobody asked for is left as fast as the change can land.
        assertFalse(strPageStepDue(plan, LeaperKimStrPage.Imu, stableMillis = 699))
        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Imu, stableMillis = 700))
    }

    @Test
    fun theCarouselNeverParksOnAnUnrecognisedPage() {
        // Including it among the wanted pages must not stall the carousel: an unrecognised page is
        // exactly what a broken marker or a damaged record looks like.
        val plan = LeaperKimStrPagePlan.Carousel(
            pages = setOf(LeaperKimStrPage.Unknown),
            dwellMillis = 8_000,
            skipMillis = 700,
        )

        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Unknown, stableMillis = 700))
    }

    @Test
    fun holdStepsUntilItArrivesAndThenStopsForever() {
        val plan = LeaperKimStrPagePlan.Hold(LeaperKimStrPage.TemperatureAndPower)
        val skip = LeaperKimStrPagePlan.DefaultSkipMillis

        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = skip))
        assertFalse(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = skip - 1))
        // On target: nothing is ever sent again, so the page keeps its full 2 Hz.
        assertFalse(strPageStepDue(plan, LeaperKimStrPage.TemperatureAndPower, skip))
        assertFalse(strPageStepDue(plan, LeaperKimStrPage.TemperatureAndPower, 600_000))
    }

    @Test
    fun theDecisionIsLevelTriggered_soTheCallerHasToRestartItsClockOnSending() {
        // This is stateless on purpose, which means it keeps saying yes for as long as the situation
        // holds. A caller that only restarted its clock when the page changed would therefore send a
        // step on every tick for the half second the change takes to land, and walk the wheel several
        // pages past the one asked for - which is exactly what happened once.
        val plan = LeaperKimStrPagePlan.Hold(LeaperKimStrPage.TemperatureAndPower)
        val skip = LeaperKimStrPagePlan.DefaultSkipMillis

        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = skip))
        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = skip + 150))
        assertTrue(strPageStepDue(plan, LeaperKimStrPage.Main, stableMillis = skip + 300))
    }

    @Test
    fun manualSendsNothingAtAll() {
        for (page in LeaperKimStrPage.entries) {
            assertFalse(strPageStepDue(LeaperKimStrPagePlan.Manual, page, 600_000))
        }
    }
}
