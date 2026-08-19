package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * A reusable sound pattern. Defined once, it can be attached to any number of alerts.
 */
data class SoundPattern(
    val id: String,
    val name: String,
    val steps: List<SoundStep> = listOf(SoundStep())
) {
    /**
     * Total length of one pass through the pattern, in milliseconds.
     */
    fun totalDurationMs(): Long {
        return steps.sumOf { (it.durationMs + it.pauseAfterMs).toLong() }
    }

    /**
     * The same pattern reduced to a single tone: the last step.
     *
     * The last and not the first, because the continuous tone at the top of an accelerating
     * curve is held by the last step, and that is the frequency the rider associates with
     * their own alarm.
     *
     * Returns a **new** pattern under its own [id]: the original may live in the library and
     * sound in other rules.
     */
    fun lastToneOnly(id: String): SoundPattern {
        val last = steps.lastOrNull() ?: return this
        return copy(id = id, steps = listOf(last))
    }

    companion object {
        fun singleBeep(isRu: Boolean = true) = SoundPattern(
            id = "std_single_beep",
            name = if (isRu) "Одиночный писк" else "Single beep",
            steps = listOf(SoundStep(frequencyHz = 1200, durationMs = 120, pauseAfterMs = 50, waveShape = WaveShape.SINE))
        )

        fun doubleBeep(isRu: Boolean = true) = SoundPattern(
            id = "std_double_beep",
            name = if (isRu) "Двойной писк" else "Double beep",
            steps = listOf(
                SoundStep(frequencyHz = 1500, durationMs = 100, pauseAfterMs = 60, waveShape = WaveShape.SINE),
                SoundStep(frequencyHz = 1500, durationMs = 100, pauseAfterMs = 50, waveShape = WaveShape.SINE)
            )
        )

        fun tripleWarningAlarm(isRu: Boolean = true) = SoundPattern(
            id = "std_triple_warning",
            name = if (isRu) "Тройной тревожный аларм" else "Triple warning alarm",
            steps = listOf(
                SoundStep(frequencyHz = 2200, durationMs = 80, pauseAfterMs = 40, waveShape = WaveShape.SQUARE),
                SoundStep(frequencyHz = 2400, durationMs = 80, pauseAfterMs = 40, waveShape = WaveShape.SQUARE),
                SoundStep(frequencyHz = 2600, durationMs = 120, pauseAfterMs = 50, waveShape = WaveShape.SQUARE)
            )
        )

        val SINGLE_SHORT_BEEP = singleBeep(true)
        val DOUBLE_BEEP = doubleBeep(true)
        val TRIPLE_WARNING_ALARM = tripleWarningAlarm(true)
    }
}
