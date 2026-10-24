package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Waveform of the generated tone.
 *
 * Both display names are carried, because a consumer picking a waveform in a picker has a label
 * slot and the library must never be the reason that slot can only be filled in one language.
 */
enum class WaveShape(val displayNameRu: String, val displayNameEn: String) {
    SINE("Мягкий (синус)", "Sine (soft beep)"),
    SQUARE("Резкий (меандр)", "Square (harsh alarm)"),
    SAWTOOTH("Агрессивный (пила)", "Sawtooth (aggressive)"),
    TRIANGLE("Нейтральный (треугольник)", "Triangle (neutral)");

    /**
     * The label in the requested language.
     *
     * No default: the language is the caller's business, and a default here would silently be one
     * language's. Kotlin defaults do not survive Swift interop either, so a default would only ever
     * have served half the consumers.
     */
    fun displayName(isRu: Boolean): String = if (isRu) displayNameRu else displayNameEn
}
