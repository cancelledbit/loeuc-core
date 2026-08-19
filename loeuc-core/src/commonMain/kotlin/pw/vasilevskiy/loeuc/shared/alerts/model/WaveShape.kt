package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Waveform of the generated tone.
 */
enum class WaveShape(val displayNameRu: String) {
    SINE("Синусоида (Мягкий писк)"),
    SQUARE("Меандр / Square (Резкий аларм)"),
    SAWTOOTH("Пила / Sawtooth (Агрессивный)"),
    TRIANGLE("Треугольная (Нейтральный)");
}
