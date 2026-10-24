package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * A group of conditions combined with AND: it holds only when every [SingleCondition] in it
 * evaluates to true.
 *
 * The default [name] is English. The name is free text the rider may overwrite and it is not part
 * of the stored shape - `AlertsJsonSerializer` flattens a group into its conditions and rebuilds it
 * from the `conditionMode` field, never writing or reading a group name - so the default is a
 * placeholder, not data, and changing it cannot invalidate anything already saved. A consumer that
 * shows the placeholder to a Russian rider asks for [defaultName] instead.
 */
data class ConditionGroup(
    override val id: String,
    val name: String = DEFAULT_NAME_EN,
    val conditions: List<SingleCondition>
) : AlertConditionItem {

    companion object {
        /** The English placeholder, and the value of [name] when the caller does not pass one. */
        const val DEFAULT_NAME_EN: String = "Condition group"

        /** The same placeholder in Russian. */
        const val DEFAULT_NAME_RU: String = "Группа условий"

        /** The placeholder name in the requested language. */
        fun defaultName(isRu: Boolean): String = if (isRu) DEFAULT_NAME_RU else DEFAULT_NAME_EN
    }
}
