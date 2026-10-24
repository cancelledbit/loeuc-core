package pw.vasilevskiy.loeuc.shared.protocol

/**
 * What is known about LeaperKim firmware before a file is seen.
 *
 * Two different things live here and must not be confused. `families` are the controller
 * platforms, with the bands their reset vector and initial stack pointer fall in: they answer
 * "is this a firmware image at all, and for which controller". `builds` is an exact sha256
 * index of known builds: it answers "is this exactly that build, byte for byte". The first
 * admits a modified image, the second does not, and a consumer should show the difference.
 *
 * The platform numbers are measured on real images, not written by hand; the manifest file is
 * generated and this class only reads it.
 */
data class LeaperKimFirmwarePlatform(
    val id: String,
    val chip: String,
    val core: String,
    val resetPcRange: LongRange,
    val initialSpRange: LongRange,
    val vfpMin: Int,
    /** Where the image lies in the wheel's flash. */
    val loadBase: Long,
    /** Where the vector table starts on this platform. */
    val vtor: Long,
)

data class LeaperKimFirmwareModel(
    val id: String,
    val familyId: String,
    /** The four digits a wheel calls itself by in telemetry: `0060` on a Sherman L. */
    val hardwareCode: String?,
)

data class LeaperKimFirmwareBuild(
    val modelId: String,
    val familyId: String,
    /** `006.0.10`, as the firmware writes it. */
    val edition: String?,
    val sizeBytes: Int,
    val format: String,
)

class LeaperKimFirmwareManifest(
    val families: List<LeaperKimFirmwarePlatform>,
    val models: Map<String, LeaperKimFirmwareModel>,
    /** Lower-case sha256 to build. */
    val builds: Map<String, LeaperKimFirmwareBuild>,
) {
    private val byHardwareCode: Map<String, LeaperKimFirmwareModel> =
        models.values.mapNotNull { model -> model.hardwareCode?.let { it to model } }.toMap()

    fun build(sha256: String): LeaperKimFirmwareBuild? = builds[sha256.lowercase()]

    fun modelForHardwareCode(code: String?): LeaperKimFirmwareModel? =
        code?.let { byHardwareCode[it] }

    fun family(id: String?): LeaperKimFirmwarePlatform? =
        id?.let { wanted -> families.firstOrNull { it.id == wanted } }

    companion object {
        const val AssetName = "leaperkim-firmware-manifest.json"

        /**
         * No platforms, so no raw image passes the structural check. Deliberate: a missing or
         * unreadable manifest must read as "nothing to check against", never as "anything goes".
         */
        val Empty = LeaperKimFirmwareManifest(emptyList(), emptyMap(), emptyMap())

        /**
         * Parses the generated manifest. Throws [IllegalArgumentException] on malformed JSON or a
         * missing required field; a caller that loads it from disk should fall back to [Empty].
         * Declared so that Swift sees a throwing call rather than a crash.
         */
        @Throws(IllegalArgumentException::class)
        fun parse(json: String): LeaperKimFirmwareManifest {
            val root = LeaperKimManifestJson.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("manifest root is not an object")

            val families = (root["families"] as? List<*>).orEmpty().map { item ->
                val entry = item.asObject("family")
                LeaperKimFirmwarePlatform(
                    id = entry.requiredString("id"),
                    chip = entry.optionalString("chip").orEmpty(),
                    core = entry.optionalString("core").orEmpty(),
                    resetPcRange = entry.requiredHexRange("resetPc"),
                    initialSpRange = entry.requiredHexRange("initialSp"),
                    vfpMin = entry.optionalInt("vfpMin"),
                    loadBase = entry.requiredString("loadBase").parseHex(),
                    vtor = entry.requiredString("vtor").parseHex(),
                )
            }

            val models = (root["models"] as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
                val id = key.toString()
                val entry = value.asObject("model $id")
                id to LeaperKimFirmwareModel(
                    id = id,
                    familyId = entry.requiredString("family"),
                    hardwareCode = entry.optionalString("hardwareCode")?.takeIf { it.isNotBlank() },
                )
            }

            val builds = (root["builds"] as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
                val sha = key.toString()
                val entry = value.asObject("build $sha")
                sha.lowercase() to LeaperKimFirmwareBuild(
                    modelId = entry.requiredString("model"),
                    familyId = entry.requiredString("family"),
                    edition = entry.optionalString("edition")?.takeIf { it.isNotBlank() },
                    sizeBytes = entry.optionalInt("size"),
                    format = entry.optionalString("format").orEmpty(),
                )
            }

            return LeaperKimFirmwareManifest(families, models, builds)
        }
    }
}

private fun Any?.asObject(what: String): Map<*, *> =
    this as? Map<*, *> ?: throw IllegalArgumentException("$what is not an object")

private fun Map<*, *>.requiredString(key: String): String = when (val value = this[key]) {
    is String -> value
    is Double -> value.toString()
    else -> throw IllegalArgumentException("missing string '$key'")
}

private fun Map<*, *>.optionalString(key: String): String? = when (val value = this[key]) {
    is String -> value
    is Double -> value.toString()
    else -> null
}

private fun Map<*, *>.optionalInt(key: String): Int = when (val value = this[key]) {
    is Double -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

private fun Map<*, *>.requiredHexRange(key: String): LongRange {
    val array = this[key] as? List<*> ?: throw IllegalArgumentException("missing array '$key'")
    val start = array.getOrNull(0) as? String ?: throw IllegalArgumentException("'$key' has no start")
    val end = array.getOrNull(1) as? String ?: throw IllegalArgumentException("'$key' has no end")
    return start.parseHex()..end.parseHex()
}

private fun String.parseHex(): Long =
    removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        ?: throw IllegalArgumentException("'$this' is not hexadecimal")

/**
 * Just enough JSON for the manifest: objects become insertion-ordered maps, arrays lists,
 * numbers doubles. The library carries no serialization dependency, and pulling one in for a
 * single generated file would cost every consumer more than these few lines.
 */
internal object LeaperKimManifestJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw IllegalArgumentException("trailing characters at ${reader.position}")
        return value
    }

    private class Reader(private val text: String) {
        var position = 0

        fun atEnd() = position >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("unexpected end of input")
            return when (val char = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) readNumber() else {
                    throw IllegalArgumentException("unexpected '$char' at $position")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw IllegalArgumentException("expected a key at $position")
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            position++
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                val char = text[position++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("unterminated escape")
                        when (val escaped = text[position++]) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw IllegalArgumentException("short \\u escape")
                                val code = text.substring(position, position + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad \\u escape at $position")
                                builder.append(code.toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '$escaped'")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            while (!atEnd() && (text[position].isDigit() || text[position] in "+-.eE")) position++
            return text.substring(start, position).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, position)) throw IllegalArgumentException("bad literal at $position")
            position += literal.length
            return value
        }

        private fun peek(): Char = if (atEnd()) throw IllegalArgumentException("unexpected end of input") else text[position]

        private fun expect(char: Char) {
            if (peek() != char) throw IllegalArgumentException("expected '$char' at $position")
            position++
        }
    }
}
