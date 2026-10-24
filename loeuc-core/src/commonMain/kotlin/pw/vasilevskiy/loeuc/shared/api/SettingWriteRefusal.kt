package pw.vasilevskiy.loeuc.shared.api

/**
 * Why a settings write was not built, worded for both interface languages.
 *
 * A refusal is shown to the rider, so it cannot be a single free-form sentence: the library
 * ships labels in both languages and a consumer picks one with [text]. The shape follows
 * `LeaperKimFirmwareIssue`, which carries the same pair for firmware problems.
 *
 * The shared reasons below are the ones every brand repeats - an unknown key, a read-only row,
 * a value out of range. A brand-specific reason is built directly with the constructor.
 */
data class SettingWriteRefusal(val english: String, val russian: String) {

    /** The wording for the interface language: Russian when [isRu], English otherwise. */
    fun text(isRu: Boolean): String = if (isRu) russian else english

    /** English, so a log line or a test failure reads the same everywhere. */
    override fun toString(): String = english

    companion object {
        /** The key names no setting this brand knows. */
        fun unknownSetting(key: String): SettingWriteRefusal = SettingWriteRefusal(
            english = "Unknown setting $key.",
            russian = "Эту настройку изменить нельзя.",
        )

        /** The row exists, but the wheel only reports it. */
        fun readOnly(): SettingWriteRefusal = SettingWriteRefusal(
            english = "Setting is read-only.",
            russian = "Настройка только для чтения.",
        )

        /** The row is not among the capabilities this wheel currently advertises. */
        fun notInCapabilities(): SettingWriteRefusal = SettingWriteRefusal(
            english = "Setting is not present in current capabilities.",
            russian = "Это колесо такую настройку не поддерживает.",
        )

        /** No write command for this row survived validation, so none is sent. */
        fun noValidatedCommand(): SettingWriteRefusal = SettingWriteRefusal(
            english = "Setting has no validated write command.",
            russian = "Эту настройку приложение пока умеет только показывать.",
        )

        /** The value falls outside the range the command was validated for. */
        fun outsideValidatedRange(): SettingWriteRefusal = SettingWriteRefusal(
            english = "Value is outside validated range.",
            russian = "Значение вне допустимого диапазона.",
        )

        /** The wheel has not said which model it is, and the command depends on that. */
        fun modelUnknown(): SettingWriteRefusal = SettingWriteRefusal(
            english = "Wheel model is unknown.",
            russian = "Колесо ещё не сообщило модель. Подождите пару секунд и повторите.",
        )

        /** The wheel never reports this row back, so a write could not be confirmed. */
        fun noReadback(): SettingWriteRefusal = SettingWriteRefusal(
            english = "The wheel does not report this setting back.",
            russian = "Колесо не сообщает эту настройку обратно, и проверить запись нечем.",
        )
    }
}
