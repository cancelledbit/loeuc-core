package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.Alert

/**
 * Where [AlertEngine] gets the alerts to evaluate.
 *
 * The engine deliberately owns no storage. Rules live wherever the application keeps them - a
 * file, a database, a settings screen the rider is editing right now - and how they are loaded,
 * migrated between versions and shown in an editor is application work, not protocol work.
 * The engine needs one thing from all of it: the list that is active on this frame.
 *
 * Implement it over your own store:
 *
 * ```kotlin
 * val engine = AlertEngine(alertSource = AlertSource { myStore.enabledAlerts() })
 * engine.processTelemetry(snapshot)
 * ```
 *
 * It is read on every frame, so keep it cheap - return an already-loaded list rather than
 * touching a disk. Passing `activeAlerts` to [AlertEngine.processTelemetry] explicitly does the
 * same job for a caller that already has the list in hand, and a caller that has neither can
 * simply call [AlertEngine.setAlerts] once.
 */
fun interface AlertSource {
    fun activeAlerts(): List<Alert>
}
