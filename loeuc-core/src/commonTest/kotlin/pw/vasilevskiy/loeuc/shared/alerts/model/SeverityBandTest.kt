package pw.vasilevskiy.loeuc.shared.alerts.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeverityBandTest {

    /** 0-2 is critical, 3-5 a warning, 6-10 informational. */
    @Test
    fun priorityCollapsesIntoThreeBands() {
        listOf(0, 1, 2).forEach { assertEquals(SeverityBand.Critical, SeverityBand.forPriority(it)) }
        listOf(3, 4, 5).forEach { assertEquals(SeverityBand.Warning, SeverityBand.forPriority(it)) }
        listOf(6, 7, 8, 9, 10).forEach { assertEquals(SeverityBand.Info, SeverityBand.forPriority(it)) }
    }

    /**
     * Severity is carried by the stroke pattern as well as the colour, so the distinction
     * survives colour blindness and direct sunlight.
     */
    @Test
    fun eachBandHasItsOwnLine() {
        assertEquals(1.5f, SeverityBand.Critical.strokeWidth)
        assertEquals(emptyList(), SeverityBand.Critical.dashIntervals)

        assertEquals(1.2f, SeverityBand.Warning.strokeWidth)
        assertEquals(listOf(3f, 2.5f), SeverityBand.Warning.dashIntervals)

        assertEquals(1.0f, SeverityBand.Info.strokeWidth)
        assertEquals(listOf(1.5f, 2.5f), SeverityBand.Info.dashIntervals)
    }

    /** Only the critical band hatches its zone: four alerts hatching would read as a barcode. */
    @Test
    fun onlyTheCriticalBandHatchesItsZone() {
        assertTrue(SeverityBand.Critical.hatchesZone)
        assertFalse(SeverityBand.Warning.hatchesZone)
        assertFalse(SeverityBand.Info.hatchesZone)
    }

    @Test
    fun worstPicksTheMostCriticalBand() {
        assertEquals(
            SeverityBand.Critical,
            SeverityBand.worst(listOf(SeverityBand.Info, SeverityBand.Critical, SeverityBand.Warning)),
        )
        assertEquals(SeverityBand.Warning, SeverityBand.worst(listOf(SeverityBand.Info, SeverityBand.Warning)))
        assertNull(SeverityBand.worst(emptyList()))
    }

    /** The Alert constructor rejects a priority outside 0..10; this must not throw regardless. */
    @Test
    fun outOfRangePrioritiesClampToTheEnds() {
        assertEquals(SeverityBand.Critical, SeverityBand.forPriority(-3))
        assertEquals(SeverityBand.Info, SeverityBand.forPriority(99))
    }
}
