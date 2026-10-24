package pw.vasilevskiy.loeuc.extension

import java.util.Locale
import kotlin.math.roundToLong

/**
 * A write the extension describes and the host assembles.
 *
 * The extension never hands the host a byte array. It names a declared command and a value;
 * the host builds the bytes from this template, the same template the rider saw on the
 * consent screen. What it guarantees is that only what was declared goes out. What it cannot
 * guarantee is that the declaration tells the truth about what those bytes do - that is what
 * the host's journal is for.
 */
class CommandTemplate(
    /** Service of [characteristic], when the characteristic UUID alone is ambiguous. */
    val service: String?,
    /** Characteristic the bytes are written to, lowercase UUID. */
    val characteristic: String,
    bytes: ByteArray,
    /** Where the command's value goes, or `null` for a fixed command. */
    val parameter: TemplateParameter?,
    /** Write with response (the default) or without. */
    val withResponse: Boolean,
) {
    private val template: ByteArray = bytes.copyOf()

    /** The template bytes, with the parameter field as declared (usually zero). */
    val bytes: ByteArray get() = template.copyOf()

    /**
     * Assembles the write for [value]. A template without a parameter takes `null`; one with a
     * parameter requires a finite value within its bounds.
     */
    fun build(value: Double?): TemplateBuild {
        val parameter = parameter ?: return if (value == null) {
            TemplateBuild.Bytes(template.copyOf())
        } else {
            TemplateBuild.Refused(RefusalReason.OUT_OF_BOUNDS)
        }
        if (parameter.width !in setOf(1, 2, 4) || parameter.offset < 0 ||
            parameter.offset > template.size - parameter.width ||
            value == null || !value.isFinite() || value !in parameter.minimum..parameter.maximum
        ) {
            return TemplateBuild.Refused(RefusalReason.OUT_OF_BOUNDS)
        }
        val raw = parameter.raw(value) ?: return TemplateBuild.Refused(RefusalReason.OUT_OF_BOUNDS)
        val out = template.copyOf()
        for (index in 0 until parameter.width) {
            val shift = 8 * if (parameter.littleEndian) index else parameter.width - 1 - index
            out[parameter.offset + index] = (raw shr shift).toByte()
        }
        return TemplateBuild.Bytes(out)
    }

    /** Stable text form, used in the consent fingerprint. */
    internal fun canonical(): String = buildString {
        append(service.orEmpty()).append('/').append(characteristic).append('/')
        template.forEach { append("%02x".format(Locale.ROOT, it.toInt() and 0xFF)) }
        append('/').append(if (withResponse) "rsp" else "norsp")
        parameter?.let { p ->
            append('/').append(p.offset).append(',').append(p.width).append(',')
                .append(if (p.littleEndian) "le" else "be").append(',').append(if (p.signed) "s" else "u")
                .append(',').append(p.scale).append(',').append(p.minimum).append(',').append(p.maximum)
        }
    }
}

/**
 * The value field inside a template: `raw = round(value / scale)`, written [width] bytes wide
 * at [offset].
 */
data class TemplateParameter(
    val offset: Int,
    val width: Int,
    val littleEndian: Boolean,
    val signed: Boolean,
    val scale: Double,
    val minimum: Double,
    val maximum: Double,
) {
    /** The raw integer for [value], or `null` when it does not fit the field. */
    fun raw(value: Double): Long? {
        val raw = (value / scale).roundToLong()
        val bits = 8 * width
        val range = if (signed) {
            -(1L shl (bits - 1))..((1L shl (bits - 1)) - 1)
        } else {
            0L..((1L shl bits) - 1)
        }
        return raw.takeIf { it in range }
    }

    internal fun fits(value: Double): Boolean = raw(value) != null
}

sealed interface TemplateBuild {
    class Bytes(val bytes: ByteArray) : TemplateBuild

    /** [reason] is one of [RefusalReason]. */
    data class Refused(val reason: Int) : TemplateBuild
}
