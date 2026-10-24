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
     * A pattern with no steps plays nothing: the alert keeps its conditions and its wheel
     * commands but makes no tone and claims no voice slot. See [silent].
     */
    val isSilent: Boolean get() = steps.isEmpty()

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
            name = if (isRu) "Тройной сигнал тревоги" else "Triple warning alarm",
            steps = listOf(
                SoundStep(frequencyHz = 2200, durationMs = 80, pauseAfterMs = 40, waveShape = WaveShape.SQUARE),
                SoundStep(frequencyHz = 2400, durationMs = 80, pauseAfterMs = 40, waveShape = WaveShape.SQUARE),
                SoundStep(frequencyHz = 2600, durationMs = 120, pauseAfterMs = 50, waveShape = WaveShape.SQUARE)
            )
        )

        /**
         * The wheel dropped off the link.
         *
         * Deliberately not an alarm timbre. Alarms are square waves above 2 kHz; this is two
         * falling sine tones, a perfect fifth down, which every ear reads as "something
         * powered off" and no ear mistakes for a PWM warning. A rider who hears it while
         * riding must not brake for it.
         *
         * Short on purpose: it says "look at your phone", not "act now".
         */
        fun linkLost(isRu: Boolean = true) = SoundPattern(
            id = "std_link_lost",
            name = if (isRu) "Колесо отключилось" else "Wheel disconnected",
            steps = listOf(
                SoundStep(frequencyHz = 784, durationMs = 140, pauseAfterMs = 40, waveShape = WaveShape.SINE),
                SoundStep(frequencyHz = 523, durationMs = 220, pauseAfterMs = 0, waveShape = WaveShape.SINE),
            ),
        )

        /**
         * No sound at all.
         *
         * For a rule that exists to change a wheel setting ("speed above 60 -> stiffer
         * pedals"): the command is the point, and a beep on every crossing is noise. The
         * engine gives such an alert no voice, so it neither sounds nor mutes a less critical
         * neighbour, and the platforms' vibration, which follows the voice, stays still too.
         */
        fun silent(isRu: Boolean = true) = SoundPattern(
            id = SILENT_ID,
            name = if (isRu) "Без звука" else "Silent",
            steps = emptyList(),
        )

        const val SILENT_ID = "std_silent"

        val LINK_LOST = linkLost(true)

        val SINGLE_SHORT_BEEP = singleBeep(true)
        val DOUBLE_BEEP = doubleBeep(true)
        val TRIPLE_WARNING_ALARM = tripleWarningAlarm(true)
    }
}
