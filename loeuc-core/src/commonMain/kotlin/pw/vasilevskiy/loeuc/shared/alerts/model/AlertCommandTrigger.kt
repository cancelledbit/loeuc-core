package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Which way an [AlertCommand] fired. The alert engine reports it to the platform code that
 * owns the BLE transport.
 */
enum class AlertCommandTrigger {
    /** The condition has just become true - write [AlertCommand.targetValue]. */
    Apply,

    /**
     * The condition has just stopped holding, through the hysteresis band - restore the
     * setting to whatever it held when the same command applied. The engine neither stores
     * nor knows the previous value: capturing it right before [Apply] is the job of the
     * platform code that performs the write.
     */
    Revert,
}
