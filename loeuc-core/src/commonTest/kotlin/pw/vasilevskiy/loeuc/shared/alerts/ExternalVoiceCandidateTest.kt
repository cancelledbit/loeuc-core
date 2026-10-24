package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertEngine
import pw.vasilevskiy.loeuc.shared.alerts.engine.VoiceCandidate
import pw.vasilevskiy.loeuc.shared.alerts.model.Alert
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
import kotlin.test.assertNull

/** Voices from outside the rule set share the one slot, and the rider's rules come first. */
class ExternalVoiceCandidateTest {

    private val external = VoiceCandidate(
        voice = AlertVoice(
            alertId = "extension:range:low",
            pattern = SoundPattern.doubleBeep(isRu = false),
            progress = 0.0,
            intervalMs = 2_000L,
        ),
        priority = AlertEngine.EXTERNAL_PRIORITY,
    )

    private val speedRule = Alert(
        id = "speed",
        name = "speed",
        priority = 5,
        type = AlertType.Repeating(1_000L),
        conditionItems = listOf(
            SingleCondition(
                "c_speed",
                MetricId.SPEED_KMH,
                ConditionTemplate(
                    id = "t_speed",
                    name = "t",
                    operator = ComparisonOperator.GREATER_OR_EQUAL,
                    targetValue = 60.0,
                    resetValue = 55.0,
                ),
            ),
        ),
        soundPattern = SoundPattern.singleBeep(isRu = false),
    )

    private fun frame(t: Long, speed: Double) = TelemetrySnapshot.create(t, MetricId.SPEED_KMH to speed)

    @Test
    fun externalVoiceSoundsWhenNoRuleWantsTheSlot() {
        var voice: AlertVoice? = null
        val engine = AlertEngine(voicePublisher = { voice = it })
        engine.setExternalCandidates(listOf(external))
        engine.processTelemetry(frame(0L, 20.0), activeAlerts = listOf(speedRule))
        assertEquals(external.voice.alertId, voice?.alertId)
    }

    @Test
    fun ruleOutranksTheExternalVoice() {
        var voice: AlertVoice? = null
        val engine = AlertEngine(voicePublisher = { voice = it })
        engine.setExternalCandidates(listOf(external))
        engine.processTelemetry(frame(0L, 65.0), activeAlerts = listOf(speedRule))
        assertEquals("speed", voice?.alertId)
    }

    @Test
    fun emptyListWithdrawsTheVoice() {
        var voice: AlertVoice? = null
        val engine = AlertEngine(voicePublisher = { voice = it }, arbiter = pw.vasilevskiy.loeuc.shared.alerts.engine.AlertVoiceArbiter(minHoldMs = 0L))
        engine.setExternalCandidates(listOf(external))
        engine.processTelemetry(frame(0L, 20.0), activeAlerts = emptyList())
        engine.setExternalCandidates(emptyList())
        engine.processTelemetry(frame(10_000L, 20.0), activeAlerts = emptyList())
        assertNull(voice)
    }
}
