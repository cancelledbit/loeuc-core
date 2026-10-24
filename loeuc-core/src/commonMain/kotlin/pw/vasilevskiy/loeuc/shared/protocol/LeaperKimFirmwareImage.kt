package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

private const val LdCmHeaderSize = 0x400
private const val LdCmMagic = "LdCm"

enum class LeaperKimFirmwareFormat {
    LdCmContainer,
    RawImage,
    Unknown,
}

/**
 * One reason a firmware file is refused or needs a warning, with the numbers behind it.
 *
 * [russian] leads with what the file is to a rider ("damaged", "not wheel firmware") and keeps
 * the numbers as technical detail; [english] is the same statement for an English interface.
 */
sealed class LeaperKimFirmwareIssue {
    abstract val russian: String
    abstract val english: String

    /** The `LdCm` header declares a size other than the file's. Blocks. */
    data class SizeMismatch(val declared: Int, val actual: Int) : LeaperKimFirmwareIssue() {
        override val russian get() = "Файл обрезан или повреждён: в заголовке указано $declared байт, в файле $actual."
        override val english get() = "File size does not match the LdCm header size: header=$declared, file=$actual."
    }

    /** The body CRC-32 does not match header `@08`. Blocks. */
    data class BodyCrcMismatch(val declared: Long, val calculated: Long) : LeaperKimFirmwareIssue() {
        override val russian get() = "Контрольная сумма не сходится — файл повреждён. Скачайте его заново. " +
            "Тех. детали: в заголовке ${hex32(declared)}, по файлу ${hex32(calculated)}."
        override val english get() = "Body CRC32 does not match: header=${hex32(declared)}, calc=${hex32(calculated)}."
    }

    /**
     * Header `@0C` is not understood. No longer reported by the analysis: the bootloader does not
     * check this field, so the note said nothing a rider could act on and was attached to every
     * container, the vendor's known builds included. The type stays for source compatibility.
     */
    data class UnknownHeaderField0c(val value: Long) : LeaperKimFirmwareIssue() {
        override val russian get() = "Поле header[0x0c] пока неизвестно: ${hex32(value)}. Отправлять только оригинальный файл."
        override val english get() = "Header field [0x0c] is not understood yet: ${hex32(value)}. Send only the original file."
    }

    /** The body is shorter than two vector-table words. Blocks. */
    data class TooShortForVectorTable(val bodySizeBytes: Int) : LeaperKimFirmwareIssue() {
        override val russian get() = "Файл слишком короткий для прошивки ($bodySizeBytes байт)."
        override val english get() = "The file is shorter than a vector table: $bodySizeBytes B."
    }

    /** Reset vector and stack pointer fit no known controller platform. Blocks. */
    data class UnknownPlatform(val resetVector: Long, val initialStackPointer: Long) : LeaperKimFirmwareIssue() {
        override val russian get() = "Файл не подходит ни к одной известной плате LeaperKim. Тех. детали: вектор сброса " +
            "${hex32(resetVector)}, начальный стек ${hex32(initialStackPointer)}."
        override val english get() = "Matches none of the known platforms: reset vector " +
            "${hex32(resetVector)}, initial stack ${hex32(initialStackPointer)}."
    }

    /** An even reset vector: a jump to ARM state, which Cortex-M does not have. Blocks. */
    data class EvenResetVector(val resetVector: Long) : LeaperKimFirmwareIssue() {
        override val russian get() = "Файл не похож на прошивку колеса (неверная точка запуска). " +
            "Тех. детали: вектор сброса ${hex32(resetVector)} чётный."
        override val english get() = "Reset vector ${hex32(resetVector)} is even: the Thumb bit is not set."
    }

    /** The entry point lies past the image: truncated, or built for another base. Blocks. */
    data class EntryPointOutsideImage(val resetVector: Long, val imageStart: Long, val imageEnd: Long) :
        LeaperKimFirmwareIssue() {
        override val russian get() = "Файл обрезан или предназначен для другой платы. Тех. детали: точка входа " +
            "${hex32(resetVector)} вне образа ${hex32(imageStart)}..${hex32(imageEnd)}."
        override val english get() = "Entry point ${hex32(resetVector)} lies outside the image " +
            "${hex32(imageStart)}..${hex32(imageEnd)}: the file is truncated or built for another base."
    }

    /** Too few handlers for a Cortex-M vector table. Blocks. */
    data class VectorTableTooShort(val vectorCount: Int) : LeaperKimFirmwareIssue() {
        override val russian get() = "Файл повреждён: служебная таблица в начале обрывается. " +
            "Тех. детали: записей в таблице векторов — $vectorCount."
        override val english get() = "The vector table ends after $vectorCount entries: too few for Cortex-M firmware."
    }

    /** One of the first system exception vectors points outside the image. Blocks. */
    data class SystemVectorOutsideImage(val index: Int, val vector: Long) : LeaperKimFirmwareIssue() {
        override val russian get() = "Файл повреждён или это не прошивка колеса. " +
            "Тех. детали: системный вектор #$index равен ${hex32(vector)} — вне образа."
        override val english get() = "System vector #$index is ${hex32(vector)}: outside the image."
    }

    /** A structurally valid raw image whose sha256 is in no manifest. Warns, does not block. */
    data object NotInManifest : LeaperKimFirmwareIssue() {
        override val russian get() = "Этой прошивки нет в нашем списке проверенных: она изменена или новее списка. " +
            "Проверено только то, что файл похож на прошивку, — прошивайте на свой риск."
        override val english get() = "The build is not in the manifest: its sha256 matches no known build. " +
            "The firmware is modified or newer than our list; only the image structure was checked."
    }
}

/**
 * Whether a file may be sent to a wheel, and on what grounds.
 *
 * There are three grounds and they differ in strength. An `LdCm` container carries its own
 * length and CRC, so the file checks itself. A raw image claims nothing about itself, so either
 * the manifest vouches for it (an exact sha256 match with a known build) or, for an unknown
 * build, only its structure does: vector table, platform, entry point. The last is weaker than
 * the other two, and a consumer has to say so in words rather than colour the button the same.
 */
sealed class LeaperKimFirmwareVerdict {
    abstract val canUpload: Boolean
    abstract val issues: List<LeaperKimFirmwareIssue>

    /** The Russian wording of [issues]. */
    val reasons: List<String> get() = issues.map { it.russian }

    /** Not firmware, or broken firmware. No way around it. */
    data class Blocked(override val issues: List<LeaperKimFirmwareIssue>) : LeaperKimFirmwareVerdict() {
        override val canUpload: Boolean get() = false
    }

    /** `LdCm`: the declared length matches the file and the body CRC matches. */
    data class ContainerVerified(override val issues: List<LeaperKimFirmwareIssue>) : LeaperKimFirmwareVerdict() {
        override val canUpload: Boolean get() = true
    }

    /** The sha256 is in the manifest: exactly that build, byte for byte. */
    data class KnownBuild(
        val build: LeaperKimFirmwareBuild,
        override val issues: List<LeaperKimFirmwareIssue>,
    ) : LeaperKimFirmwareVerdict() {
        override val canUpload: Boolean get() = true
    }

    /**
     * A raw image: platform recognised, vector table intact, build not in the manifest. A
     * modified firmware and one released after the manifest was last updated look the same.
     */
    data class Unverified(override val issues: List<LeaperKimFirmwareIssue>) : LeaperKimFirmwareVerdict() {
        override val canUpload: Boolean get() = true
    }
}

data class LeaperKimFirmwareAnalysis(
    val format: LeaperKimFirmwareFormat,
    val sizeBytes: Int,
    val declaredSizeBytes: Int?,
    val versionText: String?,
    val declaredBodyCrc32: Long?,
    val calculatedBodyCrc32: Long?,
    val unknownHeaderField0c: Long?,
    val sha256: String,
    val warningIssues: List<LeaperKimFirmwareIssue>,
    val verdict: LeaperKimFirmwareVerdict,
    /** The vector-table reading. Null only for a file that could not be read at all. */
    val inspection: LeaperKimImageInspector.Inspection?,
    /** The manifest build, when the sha256 matched. */
    val knownBuild: LeaperKimFirmwareBuild?,
) {
    /** The Russian wording of [warningIssues]. */
    val warnings: List<String> get() = warningIssues.map { it.russian }

    val isBodyCrcValid: Boolean
        get() = declaredBodyCrc32 != null && declaredBodyCrc32 == calculatedBodyCrc32

    val canUploadOriginal: Boolean
        get() = verdict.canUpload

    /**
     * Whether the image belongs to the wheel it is about to be sent to.
     *
     * Null means there is nothing to compare: the wheel has not named itself yet, or the image
     * does not. A mismatch warns and does not block, by the owner's decision: modified firmware
     * exists, and refusing here would cost more than it saves.
     */
    fun matchesWheel(wheelHardwareCode: String?): Boolean? {
        val imageCode = inspection?.summary?.hardwareCode ?: return null
        if (wheelHardwareCode.isNullOrBlank()) return null
        return imageCode == wheelHardwareCode
    }
}

/**
 * Everything that goes to the wheel, and the timings around it. The values repeat the official
 * LeaperKim app: `AT+RINTOPRO` as plain ASCII, the file verbatim in 20-byte chunks although
 * that app asks for MTU 120, bootloader entry detected as 1.5 s of telemetry silence.
 */
data class LeaperKimFirmwareUploadPlan(
    val enterBootloaderCommand: ByteArray,
    val firmwareBytes: ByteArray,
    val chunkSize: Int,
    val telemetrySilenceMillis: Long,
    val bootloaderEntryTimeoutMillis: Long,
    val resetCheckDelayMillis: Long,
    val statusLabel: String,
) {
    val totalChunks: Int
        get() = (firmwareBytes.size + chunkSize - 1) / chunkSize
}

object LeaperKimFirmwareService {
    private val ldCmMagicBytes = LdCmMagic.encodeToByteArray()

    fun analyze(bytes: ByteArray, manifest: LeaperKimFirmwareManifest): LeaperKimFirmwareAnalysis {
        val sha256 = LeaperKimFirmwareDigests.sha256Hex(bytes)
        val known = manifest.build(sha256)

        if (bytes.size >= LdCmHeaderSize && bytes.startsWith(ldCmMagicBytes)) {
            return analyzeContainer(bytes, sha256, known, manifest)
        }
        return analyzeRaw(bytes, sha256, known, manifest)
    }

    private fun analyzeContainer(
        bytes: ByteArray,
        sha256: String,
        known: LeaperKimFirmwareBuild?,
        manifest: LeaperKimFirmwareManifest,
    ): LeaperKimFirmwareAnalysis {
        val declaredSize = bytes.readUInt32Le(offset = 0x04).toInt()
        val declaredCrc = bytes.readUInt32Le(offset = 0x08)
        val unknown0c = bytes.readUInt32Le(offset = 0x0c)
        val calculatedCrc = LeaperKimFirmwareDigests.crc32(bytes, startOffset = LdCmHeaderSize)
        val version = bytes.readHeaderText(offset = 0x10, maxLength = 96)

        val blocking = buildList {
            if (declaredSize != bytes.size) add(LeaperKimFirmwareIssue.SizeMismatch(declaredSize, bytes.size))
            if (declaredCrc != calculatedCrc) add(LeaperKimFirmwareIssue.BodyCrcMismatch(declaredCrc, calculatedCrc))
        }
        // Header `@0C` is still not understood, but the bootloader does not enforce it, so it is
        // exposed as [LeaperKimFirmwareAnalysis.unknownHeaderField0c] and no longer as a warning.
        val verdict = when {
            blocking.isNotEmpty() -> LeaperKimFirmwareVerdict.Blocked(blocking)
            known != null -> LeaperKimFirmwareVerdict.KnownBuild(known, emptyList())
            else -> LeaperKimFirmwareVerdict.ContainerVerified(emptyList())
        }

        return LeaperKimFirmwareAnalysis(
            format = LeaperKimFirmwareFormat.LdCmContainer,
            sizeBytes = bytes.size,
            declaredSizeBytes = declaredSize,
            versionText = version,
            declaredBodyCrc32 = declaredCrc,
            calculatedBodyCrc32 = calculatedCrc,
            unknownHeaderField0c = unknown0c,
            sha256 = sha256,
            warningIssues = blocking,
            verdict = verdict,
            // The body summary is shown to a person, but the container verdict is decided by
            // length and CRC: when they match these are the vendor's bytes, whatever the
            // structural check thinks.
            inspection = LeaperKimImageInspector.inspect(bytes, LdCmHeaderSize, manifest),
            knownBuild = known,
        )
    }

    private fun analyzeRaw(
        bytes: ByteArray,
        sha256: String,
        known: LeaperKimFirmwareBuild?,
        manifest: LeaperKimFirmwareManifest,
    ): LeaperKimFirmwareAnalysis {
        val inspection = LeaperKimImageInspector.inspect(bytes, bodyOffset = 0, manifest = manifest)
        // `format` answers "what does this look like", not "may it go to the wheel": a stack in
        // SRAM and a reset vector in flash is not yet a platform, but no longer an arbitrary
        // file either. The verdict below decides.
        val looksLikeVectorTable = bytes.size >= 8 &&
            bytes.readUInt32Le(offset = 0).ushr(28) == 0x2L &&
            bytes.readUInt32Le(offset = 4).ushr(24) == 0x08L
        val format =
            if (looksLikeVectorTable) LeaperKimFirmwareFormat.RawImage
            else LeaperKimFirmwareFormat.Unknown

        val verdict = when {
            inspection.issues.isNotEmpty() -> LeaperKimFirmwareVerdict.Blocked(inspection.issues)
            known != null -> LeaperKimFirmwareVerdict.KnownBuild(known, emptyList())
            else -> LeaperKimFirmwareVerdict.Unverified(listOf(LeaperKimFirmwareIssue.NotInManifest))
        }

        return LeaperKimFirmwareAnalysis(
            format = format,
            sizeBytes = bytes.size,
            declaredSizeBytes = null,
            versionText = inspection.summary.edition?.let { "SysEdition:$it" },
            declaredBodyCrc32 = null,
            calculatedBodyCrc32 = null,
            unknownHeaderField0c = null,
            sha256 = sha256,
            warningIssues = verdict.issues,
            verdict = verdict,
            inspection = inspection,
            knownBuild = known,
        )
    }

    /** The plan for sending [bytes] as they are, or null when [analysis] refuses the file. */
    @ExperimentalWriteApi
    fun buildOriginalUploadPlan(
        bytes: ByteArray,
        analysis: LeaperKimFirmwareAnalysis,
    ): LeaperKimFirmwareUploadPlan? {
        if (!analysis.canUploadOriginal) {
            return null
        }
        val label = analysis.knownBuild?.let { "${it.modelId} ${it.edition ?: ""}".trim() }
            ?: analysis.inspection?.summary?.edition
            ?: analysis.versionText
            ?: analysis.sha256.take(12)
        return LeaperKimFirmwareUploadPlan(
            enterBootloaderCommand = EnterBootloaderCommand.encodeToByteArray(),
            firmwareBytes = bytes.copyOf(),
            chunkSize = ChunkSize,
            telemetrySilenceMillis = TelemetrySilenceMillis,
            bootloaderEntryTimeoutMillis = BootloaderEntryTimeoutMillis,
            resetCheckDelayMillis = ResetCheckDelayMillis,
            statusLabel = "LeaperKim firmware $label",
        )
    }

    /** The official app's programming-mode command. Stops normal telemetry; never a prelude. */
    const val EnterBootloaderCommand = "AT+RINTOPRO"

    /** The official app writes 20 bytes at a time even after asking for MTU 120. */
    const val ChunkSize = 20

    /** The official app's `isReceivingData` clears after this long without telemetry. */
    const val TelemetrySilenceMillis = 1_500L
    const val BootloaderEntryTimeoutMillis = 15_000L
    const val ResetCheckDelayMillis = 15_000L
}

/**
 * The first look at an image before it goes anywhere near a wheel.
 *
 * Newer models ship an `LdCm` container with length and CRC, where integrity is arithmetic.
 * Older ones get a bare image, and a truncated copy looks exactly like a whole one. The only
 * thing readable without a wheel is the vector table, and it says enough: on Cortex-M the first
 * word is the initial stack pointer, the second the entry point with the Thumb bit set, then
 * the system exceptions. If those words do not add up to one of the measured platforms, the
 * file is not firmware.
 *
 * No address here is written by hand: bands, load base and VTOR come from the manifest.
 */
object LeaperKimImageInspector {

    /** A Cortex-M vector table is never longer than this. */
    private const val MaxVectors = 256

    private val EditionPattern = Regex("""SysEdition:(\d{3}\.\d\.\d{2})""")

    data class Summary(
        val platform: LeaperKimFirmwarePlatform?,
        val initialStackPointer: Long,
        val resetVector: Long,
        /** The entry point's offset in the file: reset vector minus base, Thumb bit cleared. */
        val entryOffset: Long,
        /** How many vectors after the stack pointer made sense. */
        val vectorCount: Int,
        /** The address the table ended at. */
        val vectorTableEnd: Long,
        val edition: String?,
        val hardwareCode: String?,
        val modelId: String?,
        val bodySizeBytes: Int,
    )

    data class Inspection(
        val summary: Summary,
        /** Empty when the image looks whole. Anything here blocks the upload. */
        val issues: List<LeaperKimFirmwareIssue>,
    ) {
        /** The Russian wording of [issues]. */
        val problems: List<String> get() = issues.map { it.russian }
    }

    /**
     * @param bodyOffset where the image itself starts: 0 for a raw image, 0x400 inside `LdCm`.
     */
    fun inspect(
        bytes: ByteArray,
        bodyOffset: Int,
        manifest: LeaperKimFirmwareManifest,
    ): Inspection {
        val bodySize = bytes.size - bodyOffset
        val empty = Summary(
            platform = null,
            initialStackPointer = 0,
            resetVector = 0,
            entryOffset = 0,
            vectorCount = 0,
            vectorTableEnd = 0,
            edition = null,
            hardwareCode = null,
            modelId = null,
            bodySizeBytes = maxOf(bodySize, 0),
        )
        if (bodySize < 8) {
            return Inspection(empty, listOf(LeaperKimFirmwareIssue.TooShortForVectorTable(bodySize)))
        }

        val initialSp = bytes.readUInt32Le(bodyOffset)
        val resetPc = bytes.readUInt32Le(bodyOffset + 4)
        val edition = EditionPattern.find(bytes.decodeLatin1())?.groupValues?.get(1)
        val hardwareCode = edition?.let { it.substring(0, 3) + it[4] }
        val modelId = manifest.modelForHardwareCode(hardwareCode)?.id

        val platform = manifest.families.firstOrNull { family ->
            resetPc in family.resetPcRange &&
                initialSp in family.initialSpRange &&
                countVfpEncodings(bytes, family.vfpMin) >= family.vfpMin
        }

        val base = Summary(
            platform = platform,
            initialStackPointer = initialSp,
            resetVector = resetPc,
            entryOffset = 0,
            vectorCount = 0,
            vectorTableEnd = 0,
            edition = edition,
            hardwareCode = hardwareCode,
            modelId = modelId,
            bodySizeBytes = bodySize,
        )

        if (platform == null) {
            return Inspection(base, listOf(LeaperKimFirmwareIssue.UnknownPlatform(resetPc, initialSp)))
        }

        val issues = mutableListOf<LeaperKimFirmwareIssue>()
        val imageEnd = platform.loadBase + bodySize

        if (resetPc and 1L == 0L) {
            issues += LeaperKimFirmwareIssue.EvenResetVector(resetPc)
        }
        val entryOffset = (resetPc and 1L.inv()) - platform.loadBase
        if (entryOffset < 0 || entryOffset >= bodySize) {
            issues += LeaperKimFirmwareIssue.EntryPointOutsideImage(resetPc, platform.loadBase, imageEnd)
        }

        // Count handlers, not words. Zeros are legal in the table - Cortex-M leaves reserved
        // slots empty - but code follows the table, and the only way to tell the two apart is
        // that handlers stop: eight zeros in a row are the end of the table, not a hole in it.
        var vectorCount = 0
        var lastVectorIndex = 0
        var zeroRun = 0
        var index = 1
        while (index < MaxVectors && bodyOffset + index * 4 + 4 <= bytes.size) {
            val vector = bytes.readUInt32Le(bodyOffset + index * 4)
            if (vector == 0L) {
                zeroRun++
                if (zeroRun >= 8) break
                index++
                continue
            }
            val inImage = vector >= platform.loadBase && vector < imageEnd
            if (!inImage || vector and 1L == 0L) break
            zeroRun = 0
            vectorCount++
            lastVectorIndex = index
            index++
        }
        if (vectorCount < 8) {
            issues += LeaperKimFirmwareIssue.VectorTableTooShort(vectorCount)
        }
        // The first system exceptions are either zero or an address inside the image. One
        // vector pointing out is already not this firmware.
        for (systemIndex in 1..8) {
            val at = bodyOffset + systemIndex * 4
            if (at + 4 > bytes.size) break
            val vector = bytes.readUInt32Le(at)
            if (vector == 0L) continue
            if (vector < platform.loadBase || vector >= imageEnd || vector and 1L == 0L) {
                issues += LeaperKimFirmwareIssue.SystemVectorOutsideImage(systemIndex, vector)
                break
            }
        }

        return Inspection(
            base.copy(
                entryOffset = entryOffset,
                vectorCount = vectorCount,
                vectorTableEnd = platform.vtor + (lastVectorIndex + 1) * 4L,
            ),
            issues,
        )
    }

    /**
     * How many coprocessor 10/11 encodings the image holds.
     *
     * A density estimate, not a disassembly: an image built for Cortex-M4F with hardware
     * floating point has hundreds, an M3 image a handful of byte pairs that happen to decode
     * that way. It confirms the reset vector rather than deciding on its own, and stops counting
     * once the threshold is reached.
     */
    private fun countVfpEncodings(bytes: ByteArray, needed: Int): Int {
        if (needed <= 0) return 0
        var count = 0
        var at = 0
        val limit = bytes.size - 4
        while (at <= limit) {
            val first = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
            if (first and 0xEF00 == 0xED00 || first and 0xFF00 == 0xEE00) {
                val second =
                    (bytes[at + 2].toInt() and 0xff) or ((bytes[at + 3].toInt() and 0xff) shl 8)
                val coprocessor = (second shr 8) and 0xF
                if (coprocessor == 0xA || coprocessor == 0xB) {
                    count++
                    if (count >= needed) return count
                }
            }
            at += 2
        }
        return count
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun ByteArray.readUInt32Le(offset: Int): Long {
    return (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)
}

private fun ByteArray.readHeaderText(offset: Int, maxLength: Int): String? {
    val end = minOf(size, offset + maxLength)
    var stop = offset
    while (stop < end && this[stop] != 0.toByte() && this[stop] != 0xff.toByte()) stop++
    if (stop <= offset) return null
    return copyOfRange(offset, stop)
        .decodeToString()
        .trim()
        .takeIf { it.isNotBlank() }
}

/** One byte, one character: `SysEdition:` is ASCII inside an image full of non-UTF-8 bytes. */
private fun ByteArray.decodeLatin1(): String =
    buildString(size) { this@decodeLatin1.forEach { append((it.toInt() and 0xff).toChar()) } }

private fun hex32(value: Long): String {
    val digits = "0123456789ABCDEF"
    return buildString(10) {
        append("0x")
        for (shift in 28 downTo 0 step 4) append(digits[((value ushr shift) and 0xF).toInt()])
    }
}
