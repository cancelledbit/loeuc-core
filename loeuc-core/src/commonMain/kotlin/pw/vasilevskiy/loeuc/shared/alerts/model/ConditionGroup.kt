package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * A group of conditions combined with AND: it holds only when every [SingleCondition] in it
 * evaluates to true.
 */
data class ConditionGroup(
    override val id: String,
    val name: String = "Группа условий",
    val conditions: List<SingleCondition>
) : AlertConditionItem
