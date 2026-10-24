package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Charge from a lithium cell's voltage, and the pack-level scale built on top of it.
 *
 * Every brand that has to compute the charge itself computes it this way: the pack voltage
 * divided by the number of cells in series, read off one shared discharge curve. It used to
 * live inside [BegodeProtocolEngine], which was the only engine doing the arithmetic; King Song
 * needs the same answer for the same reason - a wheel without a smart BMS reports no percent
 * and no cells - and two copies of a curve are two curves the moment one of them is touched.
 */
object CellChargeCurve {

    /** Bounds a series-cell count has to fall inside to be worth dividing by. */
    const val MIN_SERIES_CELLS = 10
    const val MAX_SERIES_CELLS = 60

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
    private val points = listOf(
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

    /**
     * Charge from one cell's voltage.
     *
     * Public because a shell needs the same curve for a question the engines never ask: how much
     * charge a *ride* used, when the wheel only reports whole percent and a short ride never
     * moves that number. That is a difference between two readings rather than an absolute
     * charge, so a shell may divide by a series-cell count it guessed - and must then say the
     * answer is approximate. This object still refuses to guess the count itself; see
     * [percentFromPackVoltage].
     */
    fun percentFromCellVoltage(cellVoltage: Double): Double {
        if (cellVoltage.isNaN()) return Double.NaN
        val first = points.first()
        val last = points.last()
        if (cellVoltage <= first.first) return first.second
        if (cellVoltage >= last.first) return last.second

        val upperIndex = points.indexOfFirst { cellVoltage <= it.first }
        val (lowerVoltage, lowerPercent) = points[upperIndex - 1]
        val (upperVoltage, upperPercent) = points[upperIndex]
        val span = upperVoltage - lowerVoltage
        if (span <= 0.0) return lowerPercent
        return lowerPercent + (upperPercent - lowerPercent) * (cellVoltage - lowerVoltage) / span
    }

    /**
     * Charge from the pack voltage, or `NaN` while the series-cell count is unknown.
     *
     * There is no guess here on purpose: the count cannot be derived from one voltage reading
     * even in principle, because a full 36S reads 151.2 V and so does a 40S at 3.78 V per cell.
     * It comes from the pack itself (a smart BMS reporting cells) or from the catalogue entry
     * for the model, and when neither is available the honest answer is that the charge is
     * unknown.
     */
    fun percentFromPackVoltage(packVoltage: Double, seriesCells: Int): Double {
        if (!packVoltage.isFinite() || packVoltage <= 0.0) return Double.NaN
        if (seriesCells !in MIN_SERIES_CELLS..MAX_SERIES_CELLS) return Double.NaN
        return percentFromCellVoltage(packVoltage / seriesCells)
    }

    /** The count as an engine should store it: out-of-range means "still unknown", not a value. */
    fun normalizeSeriesCells(seriesCells: Int): Int =
        if (seriesCells in MIN_SERIES_CELLS..MAX_SERIES_CELLS) seriesCells else 0
}
