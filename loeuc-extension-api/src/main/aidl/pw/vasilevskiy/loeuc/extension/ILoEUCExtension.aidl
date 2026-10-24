package pw.vasilevskiy.loeuc.extension;

import pw.vasilevskiy.loeuc.extension.CommandResult;
import pw.vasilevskiy.loeuc.extension.DeviceDescriptor;
import pw.vasilevskiy.loeuc.extension.ILoEUCHost;
import pw.vasilevskiy.loeuc.extension.TelemetrySnapshot;

/**
 * Implemented by the extension. Every call is one way: the host never waits on code it did
 * not write. Append only, like ILoEUCHost.
 */
oneway interface ILoEUCExtension {
    /** A session with a device begins. The host binder is valid until onDetach. */
    void onAttach(ILoEUCHost host, in DeviceDescriptor device);

    /** One telemetry snapshot, at most ExtensionApi.MAX_TELEMETRY_HZ times a second. */
    void onTelemetry(in TelemetrySnapshot snapshot);

    /** The rider changed one of the settings the manifest declares. */
    void onSettingChanged(String key, double value);

    /** The outcome of an earlier ILoEUCHost.sendCommand. */
    void onCommandResult(in CommandResult result);

    /** The session is over; the reason is one of DetachReason. */
    void onDetach(int reason);
}
