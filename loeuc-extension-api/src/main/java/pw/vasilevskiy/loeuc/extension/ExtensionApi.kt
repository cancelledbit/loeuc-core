package pw.vasilevskiy.loeuc.extension

/**
 * The fixed names of the contract: what an extension declares in its own manifest so that
 * LoEUC can find it, and the limits the host holds it to.
 */
object ExtensionApi {
    /**
     * Version of this contract. The host refuses to bind a manifest asking for a newer one
     * than it knows, and serves an older one by that older version's rules.
     */
    const val VERSION = 1

    /** Intent action of the service an extension exports. */
    const val ACTION_BIND = "pw.vasilevskiy.loeuc.extension.BIND"

    /**
     * Name of the `<meta-data>` on that service. Its `android:resource` points at a raw JSON
     * resource holding the [ExtensionManifest].
     */
    const val META_DATA_MANIFEST = "pw.vasilevskiy.loeuc.extension.MANIFEST"

    /**
     * Permission the extension's service requires in `android:permission`. LoEUC declares it
     * with `protectionLevel="signature"`; an extension must never declare it itself.
     */
    const val PERMISSION_BIND = "pw.vasilevskiy.loeuc.permission.BIND_EXTENSION"

    /** Package name of the LoEUC phone app, the only host. */
    const val HOST_PACKAGE = "pw.vasilevskiy.loeuc"

    /** Ceiling and default of the telemetry rate. A manifest can only ask for less. */
    const val MAX_TELEMETRY_HZ = 5
}

/** Whether a snapshot comes from a live device or from a recording being replayed. */
object TelemetryMode {
    const val LIVE = 0

    /** A replayed dump. Commands sent during playback are refused with [RefusalReason.PLAYBACK]. */
    const val PLAYBACK = 1
}

/** Why a session ended, as passed to `onDetach`. Unknown values mean a newer host. */
object DetachReason {
    const val DEVICE_DISCONNECTED = 1
    const val DISABLED_BY_RIDER = 2

    /** The manifest changed in a way the rider has to approve again. */
    const val CONSENT_REQUIRED = 3
    const val HOST_STOPPING = 4
    const val PLAYBACK_ENDED = 5
}

/**
 * Outcome of a command. [SENT] is the only success; every other value names the reason it
 * was not sent. There is no silent outcome: a command that quietly did not go out looks
 * exactly like one that did.
 */
object RefusalReason {
    /**
     * Handed to the device. For a template: written, and acknowledged by the link where the
     * characteristic writes with response. For a named action: queued in the same write queue as
     * the app's own button, which is as far as the host can see.
     */
    const val SENT = 0

    /** The key is not among the manifest's commands. */
    const val NOT_DECLARED = 1

    /** The value is missing, not finite, or outside the declared bounds. */
    const val OUT_OF_BOUNDS = 2
    const val NOT_CONNECTED = 3
    const val PLAYBACK = 4

    /** The connected device has no such action, or lacks the template's characteristic. */
    const val UNSUPPORTED = 5

    /** A firmware or module update, or another exclusive operation, holds the link. */
    const val DEVICE_BUSY = 6
    const val RATE_LIMITED = 7

    /** The assembled bytes match a write the host never sends for this device. */
    const val FORBIDDEN_PATTERN = 8

    /** The action is refused while the device is moving, such as power-off. */
    const val MOVING = 9

    /** The rider has not approved this command, or the caller is not the bound extension. */
    const val NOT_PERMITTED = 10

    /** The write was attempted and the link reported a failure. */
    const val WRITE_FAILED = 11
}

/**
 * Named actions: commands the host assembles for whatever device is connected, so one
 * declaration works across brands and models. The list only grows.
 */
object NamedAction {
    /** Steps the headlight the same way the light button in the app does. */
    const val LIGHT_TOGGLE = "light.toggle"

    /** The device's own automatic headlight, where it has one. */
    const val LIGHT_AUTO = "light.auto"
    const val HORN = "horn"

    /** Power-off. Refused with [RefusalReason.MOVING] unless the device stands still. */
    const val SHUTDOWN = "shutdown"

    val ALL: Set<String> = setOf(LIGHT_TOGGLE, LIGHT_AUTO, HORN, SHUTDOWN)
}
