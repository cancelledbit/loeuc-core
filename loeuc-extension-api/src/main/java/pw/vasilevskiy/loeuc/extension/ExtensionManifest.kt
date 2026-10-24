package pw.vasilevskiy.loeuc.extension

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * What an extension declares about itself: the text the rider reads on the consent screen and
 * every tile, alert and command it may use. Nothing outside this declaration is reachable
 * through the host - an undeclared key is refused, not guessed at.
 *
 * Lives as a raw JSON resource referenced by [ExtensionApi.META_DATA_MANIFEST]. See [parse].
 */
data class ExtensionManifest(
    /** Stable identifier; the key of the extension's consent and journal. */
    val id: String,
    /** Contract version the extension was written against, see [ExtensionApi.VERSION]. */
    val apiVersion: Int,
    /** The author's own version, shown to the rider. */
    val version: String,
    val name: LocalizedText,
    val summary: LocalizedText,
    /** Whether snapshots should carry the position. The rider grants it separately. */
    val needsLocation: Boolean,
    /** Requested telemetry rate; see [effectiveTelemetryHz]. */
    val telemetryHz: Int,
    val settings: List<SettingSpec>,
    val tiles: List<TileSpec>,
    val alerts: List<AlertSpec>,
    val commands: List<CommandSpec>,
) {
    /** The rate the host actually delivers: the request, held within 1..[ExtensionApi.MAX_TELEMETRY_HZ]. */
    val effectiveTelemetryHz: Int get() = telemetryHz.coerceIn(1, ExtensionApi.MAX_TELEMETRY_HZ)

    fun tile(key: String): TileSpec? = tiles.firstOrNull { it.key == key }
    fun alert(key: String): AlertSpec? = alerts.firstOrNull { it.key == key }
    fun command(key: String): CommandSpec? = commands.firstOrNull { it.key == key }
    fun setting(key: String): SettingSpec? = settings.firstOrNull { it.key == key }

    /**
     * Fingerprint of everything the rider consented to: what the extension reads, draws,
     * sounds and sends. An update that changes it has to be approved again; one that only
     * changes wording, the version string or the settings does not.
     *
     * Computed from the parsed declaration rather than the file, so reformatting the JSON
     * does not ask the rider anything.
     */
    val consentHash: String
        get() {
            val canonical = buildString {
                append("id=").append(id).append('\n')
                append("location=").append(needsLocation).append('\n')
                tiles.sortedBy { it.key }.forEach { tile ->
                    append("tile=").append(tile.key).append('|').append(tile.unit)
                        .append('|').append(tile.minimum).append('|').append(tile.maximum).append('\n')
                }
                alerts.sortedBy { it.key }.forEach { alert ->
                    append("alert=").append(alert.key).append('|').append(alert.persistence.wire).append('\n')
                }
                commands.sortedBy { it.key }.forEach { command ->
                    append("command=").append(command.key).append('|')
                    when (val target = command.target) {
                        is CommandTarget.Action -> append("action:").append(target.name)
                        is CommandTarget.Template -> append("template:").append(target.template.canonical())
                    }
                    append('\n')
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

    companion object {
        private val idPattern = Regex("[a-z0-9]+([._-][a-z0-9]+)*")
        private val keyPattern = Regex("[a-z0-9]+([._-][a-z0-9]+)*")
        private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** Longest write a template may produce: the ATT ceiling for one attribute value. */
        const val MAX_TEMPLATE_BYTES = 512

        /**
         * Parses and validates a manifest. Every problem is collected rather than the first
         * one thrown, so an author fixes the file in one pass.
         *
         * See docs/extensions.md for a complete JSON manifest example.
         */
        fun parse(json: String): ManifestParseResult {
            val root = try {
                JSONObject(json)
            } catch (error: JSONException) {
                return ManifestParseResult.Invalid(listOf("not a JSON object: ${error.message}"))
            }
            val errors = mutableListOf<String>()
            val reader = Reader(errors)

            val id = reader.string(root, "id", "manifest")
            if (id != null && !idPattern.matches(id)) errors += "id: '$id' must be lowercase letters, digits and . _ -"
            val apiVersion = reader.int(root, "apiVersion", "manifest")
            if (apiVersion != null && apiVersion < 1) errors += "apiVersion: must be at least 1"
            val version = reader.string(root, "version", "manifest")
            val name = reader.text(root, "name", "manifest")
            val summary = reader.text(root, "summary", "manifest")
            val needsLocation = root.optBoolean("needsLocation", false)
            val telemetryHz = root.optInt("telemetryHz", ExtensionApi.MAX_TELEMETRY_HZ)
            if (telemetryHz < 1) errors += "telemetryHz: must be at least 1"

            val settings = reader.objects(root, "settings").mapNotNull { (where, item) -> reader.setting(item, where) }
            val tiles = reader.objects(root, "tiles").mapNotNull { (where, item) -> reader.tile(item, where) }
            val alerts = reader.objects(root, "alerts").mapNotNull { (where, item) -> reader.alert(item, where) }
            val commands = reader.objects(root, "commands").mapNotNull { (where, item) -> reader.command(item, where) }

            listOf(
                "settings" to settings.map { it.key },
                "tiles" to tiles.map { it.key },
                "alerts" to alerts.map { it.key },
                "commands" to commands.map { it.key },
            ).forEach { (section, keys) ->
                keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { key ->
                    errors += "$section: key '$key' is declared more than once"
                }
            }

            if (errors.isNotEmpty() || id == null || apiVersion == null || version == null ||
                name == null || summary == null
            ) {
                return ManifestParseResult.Invalid(errors)
            }
            return ManifestParseResult.Parsed(
                ExtensionManifest(
                    id = id,
                    apiVersion = apiVersion,
                    version = version,
                    name = name,
                    summary = summary,
                    needsLocation = needsLocation,
                    telemetryHz = telemetryHz,
                    settings = settings,
                    tiles = tiles,
                    alerts = alerts,
                    commands = commands,
                ),
            )
        }

        private class Reader(private val errors: MutableList<String>) {

            fun string(json: JSONObject, field: String, where: String): String? {
                val value = json.opt(field)
                if (value !is String || value.isBlank()) {
                    errors += "$where.$field: required text"
                    return null
                }
                return value
            }

            fun key(json: JSONObject, where: String): String? {
                val key = string(json, "key", where) ?: return null
                if (!keyPattern.matches(key)) {
                    errors += "$where.key: '$key' must be lowercase letters, digits and . _ -"
                    return null
                }
                return key
            }

            fun int(json: JSONObject, field: String, where: String): Int? {
                val value = json.opt(field)
                if (value !is Number || value.toDouble() != value.toInt().toDouble()) {
                    errors += "$where.$field: required integer"
                    return null
                }
                return value.toInt()
            }

            fun double(json: JSONObject, field: String, where: String): Double? {
                val value = json.opt(field)
                if (value !is Number || !value.toDouble().isFinite()) {
                    errors += "$where.$field: required number"
                    return null
                }
                return value.toDouble()
            }

            fun text(json: JSONObject, field: String, where: String): LocalizedText? {
                val texts = json.optJSONObject(field)?.let { value ->
                    value.keys().asSequence()
                        .mapNotNull { language -> value.optString(language).takeIf { it.isNotBlank() }?.let { language to it } }
                        .toMap()
                }.orEmpty()
                if (texts.isEmpty()) {
                    errors += "$where.$field: required object of language to text"
                    return null
                }
                return LocalizedText(texts)
            }

            fun objects(json: JSONObject, field: String): List<Pair<String, JSONObject>> {
                val value = json.opt(field) ?: return emptyList()
                if (value !is JSONArray) {
                    errors += "$field: must be an array"
                    return emptyList()
                }
                return (0 until value.length()).mapNotNull { index ->
                    val item = value.optJSONObject(index)
                    if (item == null) errors += "$field[$index]: must be an object"
                    item?.let { "$field[$index]" to it }
                }
            }

            fun bounds(min: Double?, max: Double?, where: String): Boolean {
                if (min == null || max == null) return false
                if (min > max) {
                    errors += "$where: min is greater than max"
                    return false
                }
                return true
            }

            fun setting(json: JSONObject, where: String): SettingSpec? {
                val key = key(json, where)
                val label = text(json, "label", where)
                val min = double(json, "min", where)
                val max = double(json, "max", where)
                val step = double(json, "step", where)
                val default = double(json, "default", where)
                if (!bounds(min, max, where) || key == null || label == null || step == null || default == null) return null
                if (step <= 0.0) errors += "$where.step: must be positive"
                if (default !in min!!..max!!) errors += "$where.default: outside min..max"
                if (step <= 0.0 || default !in min..max) return null
                return SettingSpec(key, label, json.optString("unit"), min, max, step, default)
            }

            fun tile(json: JSONObject, where: String): TileSpec? {
                val key = key(json, where)
                val label = text(json, "label", where)
                val min = double(json, "min", where)
                val max = double(json, "max", where)
                val decimals = json.optInt("decimals", 0)
                if (decimals !in 0..4) errors += "$where.decimals: must be within 0..4"
                if (!bounds(min, max, where) || key == null || label == null || decimals !in 0..4) return null
                return TileSpec(key, label, json.optString("unit"), decimals, min!!, max!!)
            }

            fun alert(json: JSONObject, where: String): AlertSpec? {
                val key = key(json, where)
                val text = text(json, "text", where)
                val persistenceName = json.optString("persistence", AlertPersistence.ONCE.wire)
                val persistence = AlertPersistence.entries.firstOrNull { it.wire == persistenceName }
                if (persistence == null) {
                    errors += "$where.persistence: one of ${AlertPersistence.entries.joinToString { it.wire }}"
                }
                if (key == null || text == null || persistence == null) return null
                return AlertSpec(key, text, persistence)
            }

            fun command(json: JSONObject, where: String): CommandSpec? {
                val key = key(json, where)
                val label = text(json, "label", where)
                val action = json.opt("action")
                val templateJson = json.optJSONObject("template")
                val target = when {
                    action != null && templateJson != null -> {
                        errors += "$where: declares both an action and a template"
                        null
                    }
                    action != null -> {
                        // Kept even when this version does not know the name: the extension may
                        // be newer than the host, which then refuses the command as unsupported.
                        if (action !is String || !keyPattern.matches(action)) {
                            errors += "$where.action: must be an action name such as '${NamedAction.HORN}'"
                            null
                        } else {
                            CommandTarget.Action(action)
                        }
                    }
                    templateJson != null -> template(templateJson, "$where.template")?.let(CommandTarget::Template)
                    else -> {
                        errors += "$where: needs an action or a template"
                        null
                    }
                }
                if (key == null || label == null || target == null) return null
                return CommandSpec(key, label, target)
            }

            fun template(json: JSONObject, where: String): CommandTemplate? {
                val characteristic = string(json, "characteristic", where)
                if (characteristic != null && !uuidPattern.matches(characteristic)) {
                    errors += "$where.characteristic: not a UUID"
                }
                val service = json.optString("service").takeIf { it.isNotBlank() }
                if (service != null && !uuidPattern.matches(service)) errors += "$where.service: not a UUID"
                val hex = string(json, "bytes", where)
                val bytes = hex?.let(::parseHex)
                if (hex != null && bytes == null) errors += "$where.bytes: must be an even number of hex digits"
                if (bytes != null && bytes.size !in 1..MAX_TEMPLATE_BYTES) {
                    errors += "$where.bytes: must be 1..$MAX_TEMPLATE_BYTES bytes"
                }
                val field = json.optJSONObject("parameter")?.let { parameter(it, "$where.parameter", bytes?.size) }
                val fieldValid = json.optJSONObject("parameter") == null || field != null
                val withResponse = json.optBoolean("withResponse", true)
                if (characteristic == null || !uuidPattern.matches(characteristic) || bytes == null ||
                    bytes.size !in 1..MAX_TEMPLATE_BYTES || !fieldValid ||
                    (service != null && !uuidPattern.matches(service))
                ) {
                    return null
                }
                return CommandTemplate(
                    service = service?.lowercase(Locale.ROOT),
                    characteristic = characteristic.lowercase(Locale.ROOT),
                    bytes = bytes,
                    parameter = field,
                    withResponse = withResponse,
                )
            }

            fun parameter(json: JSONObject, where: String, templateSize: Int?): TemplateParameter? {
                val offset = int(json, "offset", where)
                val width = int(json, "width", where)
                val scale = json.optDouble("scale", 1.0)
                val min = double(json, "min", where)
                val max = double(json, "max", where)
                val signed = json.optBoolean("signed", false)
                val endian = json.optString("endian", "little")
                if (width != null && width !in setOf(1, 2, 4)) errors += "$where.width: one of 1, 2, 4"
                if (endian != "little" && endian != "big") errors += "$where.endian: 'little' or 'big'"
                if (!scale.isFinite() || scale <= 0.0) errors += "$where.scale: must be a positive number"
                if (offset != null && width != null && templateSize != null &&
                    (offset < 0 || offset > templateSize - width)
                ) {
                    errors += "$where: offset $offset + width $width runs past the ${templateSize}-byte template"
                }
                if (!bounds(min, max, where) || offset == null || width == null || width !in setOf(1, 2, 4) ||
                    (endian != "little" && endian != "big") || !scale.isFinite() || scale <= 0.0 ||
                    templateSize == null || offset < 0 || offset > templateSize - width
                ) {
                    return null
                }
                val candidate = TemplateParameter(offset, width, endian == "little", signed, scale, min!!, max!!)
                if (!candidate.fits(min) || !candidate.fits(max)) {
                    errors += "$where: min..max does not fit a ${if (signed) "signed" else "unsigned"} $width-byte field at scale $scale"
                    return null
                }
                return candidate
            }

            private fun parseHex(text: String): ByteArray? {
                val clean = text.replace(" ", "")
                if (clean.length % 2 != 0 || clean.any { Character.digit(it, 16) < 0 }) return null
                return ByteArray(clean.length / 2) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
            }
        }
    }
}

sealed interface ManifestParseResult {
    data class Parsed(val manifest: ExtensionManifest) : ManifestParseResult

    /** The manifest is unusable; the host lists it as broken and never binds it. */
    data class Invalid(val errors: List<String>) : ManifestParseResult
}

/** Text in several languages, keyed by language code (`en`, `ru`, ...). */
data class LocalizedText(val byLanguage: Map<String, String>) {
    /** The text in [language], else English, else whatever language the author did provide. */
    fun resolve(language: String): String =
        byLanguage[language] ?: byLanguage["en"] ?: byLanguage.values.first()
}

data class SettingSpec(
    val key: String,
    val label: LocalizedText,
    val unit: String,
    val minimum: Double,
    val maximum: Double,
    val step: Double,
    val default: Double,
)

data class TileSpec(
    val key: String,
    val label: LocalizedText,
    val unit: String,
    val decimals: Int,
    /** A value outside minimum..maximum is not shown: the host treats it as noise. */
    val minimum: Double,
    val maximum: Double,
) {
    fun accepts(value: Double): Boolean = value.isFinite() && value in minimum..maximum
}

/** How an alert keeps asking for attention while it is active. */
enum class AlertPersistence(val wire: String) {
    /** Sounds once each time it becomes active. */
    ONCE("once"),

    /** Repeats at the host's alert cadence while active. */
    REPEAT("repeat"),

    /** Ramps with the request's intensity while active. */
    CONTINUOUS("continuous"),
}

data class AlertSpec(val key: String, val text: LocalizedText, val persistence: AlertPersistence)

data class CommandSpec(val key: String, val label: LocalizedText, val target: CommandTarget)

sealed interface CommandTarget {
    /** One of [NamedAction]: the host builds the bytes for the connected device. */
    data class Action(val name: String) : CommandTarget

    /** Bytes the extension describes itself, assembled by the host from [template]. */
    data class Template(val template: CommandTemplate) : CommandTarget
}
