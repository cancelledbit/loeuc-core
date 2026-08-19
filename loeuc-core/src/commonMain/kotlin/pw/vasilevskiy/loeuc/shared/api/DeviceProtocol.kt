package pw.vasilevskiy.loeuc.shared.api

/**
 * A protocol this library can speak, identified by a stable string id.
 *
 * Wheels and chargers are separate types on purpose. A charger has no PWM, no lean angle and no
 * trip odometer, so a [ChargerProtocol] must not be usable where a wheel is expected - the
 * compiler enforces that rather than a runtime check on a `kind` field.
 */
sealed interface DeviceProtocol {
    /** Stable id. Persisted by callers, so it never changes once released. */
    val id: String

    /** Ids this protocol used to be known by. Resolved by [fromId], never written back out. */
    val aliases: List<String>

    /** Name to show a rider. Spelled the way the manufacturer spells it. */
    val displayName: String

    companion object {
        /** Every protocol, wheels first. */
        val all: List<DeviceProtocol>
            get() = WheelProtocol.entries + ChargerProtocol.entries

        /** Resolves an id or a legacy alias. Returns null for anything unknown. */
        fun fromId(id: String): DeviceProtocol? =
            all.firstOrNull { it.id == id || id in it.aliases }
    }
}

/** Wheel protocols. */
enum class WheelProtocol(
    override val id: String,
    override val aliases: List<String>,
    override val displayName: String,
) : DeviceProtocol {
    Begode("begode", emptyList(), "Begode"),
    KingSong("kingsong", emptyList(), "KingSong"),
    Inmotion("inmotion", emptyList(), "InMotion"),

    /**
     * The id is `leaperkim_lynxs` because that is what Android has been persisting; `leaperkim`
     * is the spelling the dump detector uses for the same protocol.
     */
    LeaperKim("leaperkim_lynxs", listOf("leaperkim"), "LeaperKim"),

    /** Speaks the LeaperKim protocol; only the advertisement tells the two apart. */
    Nosfet("nosfet", emptyList(), "NOSFET"),

    Ninebot("ninebot_z", emptyList(), "Ninebot Z"),
    SolowheelXtreme("solowheel_xtreme", emptyList(), "Solowheel Xtreme"),
}

/** Charger protocols. */
enum class ChargerProtocol(
    override val id: String,
    override val aliases: List<String>,
    override val displayName: String,
) : DeviceProtocol {
    HwSmart("hw_charger", emptyList(), "HW Smart Charger"),
    SkatCanControl("skat_charger", emptyList(), "SKAT / CAN-Control"),
}
