package example.extension

import android.util.Log
import pw.vasilevskiy.loeuc.extension.CommandResult
import pw.vasilevskiy.loeuc.extension.DeviceDescriptor
import pw.vasilevskiy.loeuc.extension.ExtensionMetric
import pw.vasilevskiy.loeuc.extension.LoEUCExtensionService
import pw.vasilevskiy.loeuc.extension.TelemetrySnapshot

/**
 * Estimates the distance left from the battery level, shows it as a row of the Extensions tile,
 * warns below a reserve and honks once when the estimate reaches zero.
 *
 * Everything arrives on the main thread, in order, and only from LoEUC: the base class has
 * already checked the caller's signature.
 */
class RangeExtensionService : LoEUCExtensionService() {

    private var kmPerPercent = 0.8
    private var reserveKm = 5.0
    private var warned = false
    private var honked = false

    override fun onSessionStart(host: Host, device: DeviceDescriptor) {
        warned = false
        honked = false
        Log.i(LogTag, "session with ${device.name ?: "a device"}")
    }

    override fun onSettingChanged(key: String, value: Double) {
        when (key) {
            "km_per_percent" -> kmPerPercent = value
            "reserve_km" -> reserveKm = value
        }
    }

    override fun onTelemetry(snapshot: TelemetrySnapshot) {
        val session = host ?: return
        val battery = snapshot.value(ExtensionMetric.BATTERY_PERCENT) ?: return
        val range = (battery * kmPerPercent).coerceIn(0.0, 500.0)
        session.publishTile("range", range)

        val low = range < reserveKm
        if (low != warned) {
            session.raiseAlert("low_range", active = low)
            warned = low
        }
        // A replayed recording gets the tile and the alert but no command: the host would
        // refuse it with PLAYBACK anyway, and saying so here keeps the journal clean.
        if (range <= 0.0 && !honked && !snapshot.isPlayback) {
            session.sendCommand("horn")
            honked = true
        }
    }

    override fun onCommandResult(result: CommandResult) {
        if (!result.sent) Log.w(LogTag, "${result.key} refused: ${result.outcome}")
    }

    override fun onSessionEnd(reason: Int) {
        Log.i(LogTag, "session ended: $reason")
    }

    private companion object {
        const val LogTag = "RangeExtension"
    }
}
