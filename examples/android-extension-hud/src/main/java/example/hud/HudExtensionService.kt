package example.hud

import android.util.Log
import pw.vasilevskiy.loeuc.extension.CommandResult
import pw.vasilevskiy.loeuc.extension.DeviceDescriptor
import pw.vasilevskiy.loeuc.extension.LoEUCExtensionService
import pw.vasilevskiy.loeuc.extension.TelemetrySnapshot

/**
 * The bridge: LoEUC on one side, the HUD on the other.
 *
 * - Telemetry: every snapshot (5 a second, the most LoEUC sends) becomes one HudPacket write.
 * - Button: a short press asks LoEUC for the horn, a long press for the headlight. The
 *   extension never builds wheel bytes - it names a declared command and LoEUC does the rest,
 *   for whatever brand is connected.
 * - Status: the HUD's battery is a row of the Extensions tile; losing the HUD mid-ride raises
 *   the declared alert, through LoEUC's own alert engine.
 *
 * The HUD link lives exactly as long as a LoEUC session: nothing scans while no wheel is
 * connected.
 */
class HudExtensionService : LoEUCExtensionService(), HudLink.Listener {

    private lateinit var link: HudLink
    private var hadLink = false

    override fun onCreate() {
        super.onCreate()
        link = HudLink(this, this)
    }

    override fun onSessionStart(host: Host, device: DeviceDescriptor) {
        Log.i(LogTag, "session with ${device.name ?: "a device"}, connecting to the HUD")
        hadLink = false
        link.start()
    }

    override fun onTelemetry(snapshot: TelemetrySnapshot) {
        link.send(HudPacket.encode(HudValues.from(snapshot)))
    }

    override fun onSettingChanged(key: String, value: Double) {
        if (key == "brightness") link.setBrightness(value.toInt())
    }

    override fun onCommandResult(result: CommandResult) {
        // A refused horn is worth knowing about: during playback, while moving (power-off), or
        // when the wheel has no such command. The rider sees the same in LoEUC's journal.
        if (!result.sent) Log.w(LogTag, "${result.key} refused: ${result.outcome}")
    }

    override fun onSessionEnd(reason: Int) {
        link.stop()
        hadLink = false
    }

    override fun onDestroy() {
        link.stop()
        super.onDestroy()
    }

    // ── HUD side ──────────────────────────────────────────────────────────────

    override fun onLinkChanged(connected: Boolean) {
        val session = host ?: return
        // Only a link that existed and then dropped is an alert; "not found yet" is not.
        if (connected) {
            if (hadLink) session.raiseAlert("hud_lost", active = false)
            hadLink = true
        } else if (hadLink) {
            session.raiseAlert("hud_lost", active = true)
            session.publishTile("hud_battery", Double.NaN)
        }
    }

    override fun onEvent(event: HudEvent) {
        val session = host ?: return
        when (event) {
            HudEvent.ShortPress -> session.sendCommand("horn")
            HudEvent.LongPress -> session.sendCommand("light")
            is HudEvent.Battery -> session.publishTile("hud_battery", event.percent.toDouble())
        }
    }

    private companion object {
        const val LogTag = "HudBridge"
    }
}
