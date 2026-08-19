package pw.vasilevskiy.loeuc.shared.api

/**
 * Marks a builder that produces bytes meant to be written to a wheel or a charger, as opposed
 * to bytes that only ask a device to report something.
 *
 * Reading a wheel is safe: a decoder that gets a frame wrong shows a wrong number. Writing is
 * not. These byte sequences come from reverse engineering, and a wrong one lands on a machine
 * a person is standing on: a setting can change how the wheel balances, a light command on the
 * wrong model can be a different command entirely, and a shutdown command is a shutdown
 * command. Nothing here is validated against every firmware of every model.
 *
 * Opting in is the acknowledgement that the caller - not this library - decides whether a
 * given byte sequence is safe to send, and that the caller owns the transport that sends it.
 * Nothing in this library ever opens a connection or writes on its own.
 *
 * ```kotlin
 * @OptIn(ExperimentalWriteApi::class)
 * fun lightsOn(engine: KingSongProtocolEngine) = buildKingSongLightCommand(command = 0)
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Produces bytes that change a wheel's state. Read the KDoc on ExperimentalWriteApi " +
        "before opting in: these commands are reverse-engineered and the caller owns the risk.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class ExperimentalWriteApi
