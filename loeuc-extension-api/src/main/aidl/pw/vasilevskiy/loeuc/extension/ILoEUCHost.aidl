package pw.vasilevskiy.loeuc.extension;

import pw.vasilevskiy.loeuc.extension.AlertRequest;
import pw.vasilevskiy.loeuc.extension.CommandValue;
import pw.vasilevskiy.loeuc.extension.HostCapabilities;
import pw.vasilevskiy.loeuc.extension.TileValue;

/**
 * Implemented by LoEUC and handed to the extension in ILoEUCExtension.onAttach.
 *
 * Append only: a method added later goes to the end, and nothing published is ever changed or
 * removed. Every key names an entry the extension declared in its manifest; an undeclared key
 * is refused, never guessed at.
 */
interface ILoEUCHost {
    /** What the host can do for this extension right now. */
    HostCapabilities capabilities();

    /** A new value for a declared tile. */
    oneway void publishTile(String key, in TileValue value);

    /** Starts or stops a declared alert. The rider's own alert rules still win the speaker. */
    oneway void raiseAlert(String key, in AlertRequest request);

    /**
     * Asks the host to send a declared command. The answer arrives later through
     * ILoEUCExtension.onCommandResult with the same request id, so a slow write never holds
     * the caller's thread.
     */
    oneway void sendCommand(String key, in CommandValue value);
}
