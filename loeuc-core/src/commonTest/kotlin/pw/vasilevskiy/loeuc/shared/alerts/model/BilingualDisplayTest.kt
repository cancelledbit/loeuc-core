package pw.vasilevskiy.loeuc.shared.alerts.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The library must never hand a consumer a Russian-only string where it has a display slot.
 *
 * Every leak this guards used to be a Russian literal with no English counterpart, or a
 * `isRu: Boolean = true` default that made Russian the thing a caller got by saying nothing.
 */
class BilingualDisplayTest {

    private fun hasCyrillic(text: String): Boolean = text.any { it in 'Ѐ'..'ӿ' }

    @Test
    fun everyWaveShapeCarriesBothLanguages() {
        for (shape in WaveShape.entries) {
            assertTrue(shape.displayNameRu.isNotBlank(), "${shape.name} has no Russian label")
            assertTrue(shape.displayNameEn.isNotBlank(), "${shape.name} has no English label")
            assertFalse(
                hasCyrillic(shape.displayNameEn),
                "${shape.name} English label is not English: ${shape.displayNameEn}",
            )
            assertEquals(shape.displayNameRu, shape.displayName(isRu = true))
            assertEquals(shape.displayNameEn, shape.displayName(isRu = false))
        }
    }

    @Test
    fun everyMetricCarriesBothLanguages() {
        for (metric in MetricId.entries) {
            assertTrue(metric.displayNameEn.isNotBlank(), "${metric.name} has no English label")
            assertFalse(
                hasCyrillic(metric.displayNameEn),
                "${metric.name} English label is not English: ${metric.displayNameEn}",
            )
            assertFalse(
                hasCyrillic(metric.unit(isRu = false)),
                "${metric.name} English unit is not English: ${metric.unit(isRu = false)}",
            )
            assertEquals(metric.displayNameRu, metric.displayName(isRu = true))
            assertEquals(metric.displayNameEn, metric.displayName(isRu = false))
        }
    }

    @Test
    fun everyComparisonOperatorCarriesBothLanguages() {
        for (operator in ComparisonOperator.entries) {
            assertTrue(operator.displayNameEn.isNotBlank(), "${operator.name} has no English label")
            assertFalse(
                hasCyrillic(operator.displayNameEn),
                "${operator.name} English label is not English: ${operator.displayNameEn}",
            )
            assertEquals(operator.displayNameRu, operator.displayName(isRu = true))
            assertEquals(operator.displayNameEn, operator.displayName(isRu = false))
        }
    }

    @Test
    fun theConditionGroupPlaceholderIsNotRussianByDefault() {
        val group = ConditionGroup(id = "g", conditions = emptyList())

        assertFalse(hasCyrillic(group.name), group.name)
        assertEquals(ConditionGroup.DEFAULT_NAME_EN, group.name)
        assertEquals(ConditionGroup.DEFAULT_NAME_EN, ConditionGroup.defaultName(isRu = false))
        assertEquals(ConditionGroup.DEFAULT_NAME_RU, ConditionGroup.defaultName(isRu = true))
    }

    @Test
    fun theAutoTemplateNameFollowsTheLanguageItIsAsked() {
        assertEquals(
            "Скорость > 50.0",
            SingleCondition.autoTemplateName(
                metric = MetricId.SPEED_KMH,
                operator = ComparisonOperator.GREATER_THAN,
                targetValue = 50.0,
                isRu = true,
            ),
        )
        assertEquals(
            "Speed > 50.0",
            SingleCondition.autoTemplateName(
                metric = MetricId.SPEED_KMH,
                operator = ComparisonOperator.GREATER_THAN,
                targetValue = 50.0,
                isRu = false,
            ),
        )
    }

    @Test
    fun createWithoutANameDoesNotInventARussianOne() {
        val condition = SingleCondition.create(
            id = "c1",
            metric = MetricId.PWM_PERCENT,
            operator = ComparisonOperator.GREATER_OR_EQUAL,
            targetValue = 80.0,
        )

        assertFalse(hasCyrillic(condition.template.name), condition.template.name)
        assertEquals("PWM >= 80.0", condition.template.name)
    }

    @Test
    fun createStillHonoursANameTheCallerPicked() {
        val condition = SingleCondition.create(
            id = "c1",
            metric = MetricId.PWM_PERCENT,
            operator = ComparisonOperator.GREATER_OR_EQUAL,
            targetValue = 80.0,
            templateName = SingleCondition.autoTemplateName(
                metric = MetricId.PWM_PERCENT,
                operator = ComparisonOperator.GREATER_OR_EQUAL,
                targetValue = 80.0,
                isRu = true,
            ),
        )

        assertEquals("ШИМ (PWM) >= 80.0", condition.template.name)
    }
}
