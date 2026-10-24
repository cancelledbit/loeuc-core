package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertEngine
import pw.vasilevskiy.loeuc.shared.alerts.model.Alert
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertCommand
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertCommandTrigger
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.ComparisonOperator
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A rule whose only job is a wheel command ("speed above 60 -> stiffer pedals") must be able to
 * do it without a beep on every crossing.
 */
class SilentAlertTest {

    private fun speedAlert(
        id: String,
        type: AlertType,
        pattern: SoundPattern,
        priority: Int = 5,
        commands: List<AlertCommand> = emptyList(),
    ) = Alert(
        id = id,
        name = id,
        priority = priority,
        wheelId = if (commands.isEmpty()) null else "wheel",
        type = type,
        conditionItems = listOf(
            SingleCondition(
                "c_$id",
                MetricId.SPEED_KMH,
                ConditionTemplate(
                    id = "t_$id",
                    name = "t",
                    operator = ComparisonOperator.GREATER_OR_EQUAL,
                    targetValue = 60.0,
                    resetValue = 55.0,
                ),
            )
        ),
        soundPattern = pattern,
        commands = commands,
    )

    private fun frame(t: Long, speed: Double) =
        TelemetrySnapshot.create(t, MetricId.SPEED_KMH to speed)

    @Test
    fun silentPatternHasNoStepsAndSaysSo() {
        val silent = SoundPattern.silent(isRu = false)
        assertTrue(silent.isSilent)
        assertTrue(silent.steps.isEmpty())
        assertEquals(0L, silent.totalDurationMs())
        assertEquals(SoundPattern.SILENT_ID, silent.id)
        assertFalse(SoundPattern.singleBeep().isSilent)
    }

    @Test
    fun silentAlertRunsItsCommandsButNeverTakesTheVoice() {
        val voices = mutableListOf<AlertVoice?>()
        val commands = mutableListOf<AlertCommandTrigger>()
        val engine = AlertEngine(
            voicePublisher = { voices.add(it) },
            commandExecutor = { _, _, trigger -> commands.add(trigger) },
        )
        val types = listOf(AlertType.OneShot(), AlertType.Repeating(1_000L))
        for (type in types) {
            voices.clear()
            commands.clear()
            engine.clear()
            val alert = speedAlert(
                id = "pedals",
                type = type,
                pattern = SoundPattern.silent(),
                commands = listOf(AlertCommand("cmd", "pedal_hardness", 80)),
            )
            var t = 1_000L
            val events = mutableListOf<Int>()
            for (speed in listOf(40.0, 61.0, 65.0, 70.0, 65.0, 50.0, 62.0, 40.0)) {
                t += 500L
                events.add(engine.processTelemetry(frame(t, speed), currentWheelId = "wheel", activeAlerts = listOf(alert)).size)
            }
            assertTrue(voices.all { it == null }, "a silent alert published a voice for $type")
            assertTrue(events.all { it == 0 }, "a silent alert produced a trigger event for $type")
            assertEquals(
                listOf(
                    AlertCommandTrigger.Apply, AlertCommandTrigger.Revert,
                    AlertCommandTrigger.Apply, AlertCommandTrigger.Revert,
                ),
                commands,
                "commands must follow the condition edges for $type",
            )
        }
    }

    @Test
    fun silentAlertDoesNotMuteALessCriticalNeighbour() {
        var lastVoice: AlertVoice? = null
        val engine = AlertEngine(voicePublisher = { lastVoice = it })
        val silentCritical = speedAlert("silent", AlertType.Repeating(1_000L), SoundPattern.silent(), priority = 0)
        val audibleMinor = speedAlert("beep", AlertType.Repeating(1_000L), SoundPattern.doubleBeep(), priority = 9)

        engine.processTelemetry(frame(1_000L, 70.0), activeAlerts = listOf(silentCritical, audibleMinor))

        assertEquals("beep", lastVoice?.alertId)
    }

    @Test
    fun audibleAlertStillSounds() {
        var lastVoice: AlertVoice? = null
        val engine = AlertEngine(voicePublisher = { lastVoice = it })
        engine.processTelemetry(
            frame(1_000L, 70.0),
            activeAlerts = listOf(speedAlert("beep", AlertType.OneShot(), SoundPattern.singleBeep())),
        )
        assertEquals("beep", lastVoice?.alertId)

        engine.processTelemetry(
            frame(1_100L, 70.0),
            activeAlerts = listOf(speedAlert("beep", AlertType.OneShot(), SoundPattern.silent())),
        )
        assertNull(lastVoice)
    }
}
