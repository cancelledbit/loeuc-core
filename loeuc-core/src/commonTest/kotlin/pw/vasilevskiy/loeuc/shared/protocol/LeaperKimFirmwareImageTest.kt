package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checking a firmware file before a byte of it reaches a wheel: the `LdCm` container, the raw
 * image vector table, the manifest, the digests, and the upload plan. A mistake here costs a
 * wheel, so each check has its own test.
 */
@OptIn(ExperimentalWriteApi::class)
class LeaperKimFirmwareImageTest {

    // ---- digests ------------------------------------------------------------------------

    @Test
    fun sha256_matches_the_standard_vectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            LeaperKimFirmwareDigests.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LeaperKimFirmwareDigests.sha256Hex("abc".encodeToByteArray()),
        )
        // Two blocks: the padding spills over the first.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            LeaperKimFirmwareDigests.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun sha256_of_a_long_message_matches() {
        // One million 'a' - the FIPS long-message vector, far past any single-block shortcut.
        val message = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            LeaperKimFirmwareDigests.sha256Hex(message),
        )
    }

    @Test
    fun crc32_matches_the_check_value_and_honours_the_offset() {
        assertEquals(0xCBF43926L, LeaperKimFirmwareDigests.crc32("123456789".encodeToByteArray(), 0))
        assertEquals(0xCBF43926L, LeaperKimFirmwareDigests.crc32("xx123456789".encodeToByteArray(), 2))
    }

    // ---- LdCm container -----------------------------------------------------------------

    @Test
    fun a_valid_container_is_accepted_and_its_header_is_read() {
        val firmware = container()
        val analysis = LeaperKimFirmwareService.analyze(firmware, LeaperKimFirmwareManifest.Empty)

        assertEquals(LeaperKimFirmwareFormat.LdCmContainer, analysis.format)
        assertEquals(firmware.size, analysis.declaredSizeBytes)
        assertTrue(analysis.isBodyCrcValid)
        assertTrue(analysis.canUploadOriginal)
        assertTrue(analysis.verdict is LeaperKimFirmwareVerdict.ContainerVerified)
        assertEquals("SysEdition:0090xx", analysis.versionText)
        assertEquals(0x8D4B8206L, analysis.unknownHeaderField0c)
        assertNotNull(LeaperKimFirmwareService.buildOriginalUploadPlan(firmware, analysis))
    }

    @Test
    fun a_container_with_a_wrong_crc_is_blocked() {
        val firmware = container().also { it[0x08] = (it[0x08].toInt() xor 0xff).toByte() }
        val analysis = LeaperKimFirmwareService.analyze(firmware, LeaperKimFirmwareManifest.Empty)

        assertFalse(analysis.isBodyCrcValid)
        assertFalse(analysis.canUploadOriginal)
        assertTrue(analysis.verdict.issues.any { it is LeaperKimFirmwareIssue.BodyCrcMismatch })
        assertNull(LeaperKimFirmwareService.buildOriginalUploadPlan(firmware, analysis))
    }

    @Test
    fun a_size_mismatch_blocks_even_with_a_valid_crc() {
        val firmware = container()
        firmware.putUInt32Le(0x04, (firmware.size + 16).toLong())
        val analysis = LeaperKimFirmwareService.analyze(firmware, LeaperKimFirmwareManifest.Empty)

        assertTrue(analysis.isBodyCrcValid)
        assertFalse(analysis.canUploadOriginal)
        assertTrue(analysis.warnings.any { it.contains("Файл обрезан или повреждён") })
    }

    @Test
    fun the_unknown_header_field_is_neither_a_block_nor_a_warning() {
        val analysis = LeaperKimFirmwareService.analyze(container(), LeaperKimFirmwareManifest.Empty)

        // The bootloader does not check header `@0C`, so the field is still read and exposed but
        // no longer attached to every container as a note the rider cannot act on.
        assertTrue(analysis.canUploadOriginal)
        assertEquals(0x8D4B8206L, analysis.unknownHeaderField0c)
        assertEquals(emptyList(), analysis.warnings)
        assertEquals(emptyList(), analysis.verdict.issues)
    }

    @Test
    fun a_version_padded_with_erased_flash_stops_at_the_padding() {
        val firmware = container()
        for (offset in (0x10 + "SysEdition:0090xx".length) until 0x400) firmware[offset] = 0xff.toByte()
        firmware.putUInt32Le(0x08, LeaperKimFirmwareDigests.crc32(firmware, 0x400))

        assertEquals("SysEdition:0090xx", LeaperKimFirmwareService.analyze(firmware, LeaperKimFirmwareManifest.Empty).versionText)
    }

    @Test
    fun a_container_shorter_than_its_header_is_not_a_container() {
        val truncated = ByteArray(0x40)
        "LdCm".encodeToByteArray().copyInto(truncated)
        truncated.putUInt32Le(0x04, 0x40)

        val analysis = LeaperKimFirmwareService.analyze(truncated, LeaperKimFirmwareManifest.Empty)

        assertEquals(LeaperKimFirmwareFormat.Unknown, analysis.format)
        assertFalse(analysis.canUploadOriginal)
    }

    @Test
    fun an_empty_file_is_rejected_without_crashing() {
        val analysis = LeaperKimFirmwareService.analyze(ByteArray(0), LeaperKimFirmwareManifest.Empty)

        assertEquals(LeaperKimFirmwareFormat.Unknown, analysis.format)
        assertFalse(analysis.canUploadOriginal)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", analysis.sha256)
    }

    // ---- raw image ----------------------------------------------------------------------

    @Test
    fun a_raw_image_is_blocked_when_there_is_nothing_to_check_it_against() {
        val firmware = ByteArray(64)
        firmware.putUInt32Le(0, 0x20017C00)
        firmware.putUInt32Le(4, 0x0800C725)

        val analysis = LeaperKimFirmwareService.analyze(firmware, LeaperKimFirmwareManifest.Empty)

        assertEquals(LeaperKimFirmwareFormat.RawImage, analysis.format)
        assertTrue(analysis.verdict is LeaperKimFirmwareVerdict.Blocked)
    }

    @Test
    fun a_structurally_valid_raw_image_outside_the_manifest_is_unverified() {
        val analysis = LeaperKimFirmwareService.analyze(genBImage(), manifest())

        assertTrue(analysis.canUploadOriginal)
        assertTrue(analysis.verdict is LeaperKimFirmwareVerdict.Unverified)
        assertEquals(listOf(LeaperKimFirmwareIssue.NotInManifest), analysis.verdict.issues)
        assertEquals("gen-b", analysis.inspection?.summary?.platform?.id)
        assertEquals("SysEdition:006.0.10", analysis.versionText)
        assertEquals(0x204L, analysis.inspection?.summary?.entryOffset)
        assertEquals(61, analysis.inspection?.summary?.vectorCount)
    }

    @Test
    fun a_known_build_is_named_by_its_hash() {
        val image = genBImage()
        val sha = LeaperKimFirmwareDigests.sha256Hex(image)

        val verdict = LeaperKimFirmwareService.analyze(image, manifest(knownSha = sha.uppercase())).verdict

        assertTrue(verdict is LeaperKimFirmwareVerdict.KnownBuild)
        assertEquals("sherman_l", verdict.build.modelId)
    }

    @Test
    fun a_truncated_raw_image_is_blocked_by_its_entry_point() {
        val analysis = LeaperKimFirmwareService.analyze(genBImage(sizeBytes = 256), manifest())

        assertFalse(analysis.canUploadOriginal)
        assertTrue(analysis.verdict.issues.any { it is LeaperKimFirmwareIssue.EntryPointOutsideImage })
        assertTrue(analysis.verdict.reasons.any { it.contains("вне образа") })
    }

    @Test
    fun an_even_reset_vector_and_a_foreign_system_vector_are_blocked() {
        val even = genBImage().also { it.putUInt32Le(4, 0x08004204) }
        assertTrue(inspect(even).issues.any { it is LeaperKimFirmwareIssue.EvenResetVector })

        val foreign = genBImage(sizeBytes = 1024).also { it.putUInt32Le(8, 0x08009001) }
        assertTrue(inspect(foreign).issues.any { it is LeaperKimFirmwareIssue.SystemVectorOutsideImage })
    }

    @Test
    fun an_unknown_platform_says_so_in_both_languages() {
        val issue = inspect(genBImage().also { it.putUInt32Le(4, 0x08009001) }).issues.single()

        assertEquals(
            "Файл не подходит ни к одной известной плате LeaperKim. Тех. детали: вектор сброса 0x08009001, начальный стек 0x20005000.",
            issue.russian,
        )
        assertEquals("Matches none of the known platforms: reset vector 0x08009001, initial stack 0x20005000.", issue.english)
    }

    @Test
    fun matches_wheel_compares_hardware_codes_only_when_it_can() {
        val analysis = LeaperKimFirmwareService.analyze(genBImage(), manifest())

        assertEquals(true, analysis.matchesWheel("0060"))
        assertEquals(false, analysis.matchesWheel("0070"))
        assertNull(analysis.matchesWheel(null))
    }

    // ---- plan ---------------------------------------------------------------------------

    @Test
    fun the_plan_repeats_the_official_transport() {
        val firmware = container()
        val plan = LeaperKimFirmwareService.buildOriginalUploadPlan(
            firmware,
            LeaperKimFirmwareService.analyze(firmware, LeaperKimFirmwareManifest.Empty),
        )!!

        assertContentEquals(
            byteArrayOf(0x41, 0x54, 0x2B, 0x52, 0x49, 0x4E, 0x54, 0x4F, 0x50, 0x52, 0x4F),
            plan.enterBootloaderCommand,
        )
        assertEquals(20, plan.chunkSize)
        assertEquals(1_500L, plan.telemetrySilenceMillis)
        assertEquals(15_000L, plan.bootloaderEntryTimeoutMillis)
        assertEquals(15_000L, plan.resetCheckDelayMillis)
        assertContentEquals(firmware, plan.firmwareBytes)
        assertEquals(54, plan.totalChunks)
        assertTrue(plan.statusLabel.contains("SysEdition:0090xx"))

        firmware[0x400] = (firmware[0x400].toInt() xor 0xff).toByte()
        assertNotEquals(firmware[0x400], plan.firmwareBytes[0x400], "the plan holds a copy")
    }

    // ---- manifest -----------------------------------------------------------------------

    @Test
    fun the_manifest_parser_reads_every_field() {
        val parsed = manifest(knownSha = "AB")

        val family = parsed.families.single()
        assertEquals("gen-b", family.id)
        assertEquals("gd32f103", family.chip)
        assertEquals(0x08004200L..0x080042FFL, family.resetPcRange)
        assertEquals(0x08004000L, family.loadBase)
        assertEquals("0060", parsed.models.getValue("sherman_l").hardwareCode)
        assertEquals("sherman_l", parsed.modelForHardwareCode("0060")?.id)
        val build = parsed.build("ab")!!
        assertEquals("006.0.10", build.edition)
        assertEquals(4096, build.sizeBytes)
        assertEquals(family, parsed.family("gen-b"))
    }

    @Test
    fun the_manifest_parser_handles_escapes_and_refuses_garbage() {
        val parsed = LeaperKimFirmwareManifest.parse(
            """{"note": ["a \"quoted\" \\ A line"], "families": [], "models": {"x": {"family": "f", "hardwareCode": ""}}}""",
        )
        assertNull(parsed.models.getValue("x").hardwareCode)
        assertFailsWith<IllegalArgumentException> { LeaperKimFirmwareManifest.parse("{\"families\": [") }
        assertFailsWith<IllegalArgumentException> { LeaperKimFirmwareManifest.parse("[]") }
        assertFailsWith<IllegalArgumentException> {
            LeaperKimFirmwareManifest.parse("""{"families": [{"id": "a"}]}""")
        }
    }

    // ---- fixtures -----------------------------------------------------------------------

    private fun inspect(bytes: ByteArray) = LeaperKimImageInspector.inspect(bytes, 0, manifest())

    private fun container(sizeBytes: Int = 0x430): ByteArray {
        val firmware = ByteArray(sizeBytes) { index -> (index and 0xff).toByte() }
        "LdCm".encodeToByteArray().copyInto(firmware)
        firmware.putUInt32Le(0x04, firmware.size.toLong())
        firmware.putUInt32Le(0x0c, 0x8D4B8206)
        val version = "SysEdition:0090xx"
        version.encodeToByteArray().copyInto(firmware, destinationOffset = 0x10)
        firmware[0x10 + version.length] = 0
        firmware.putUInt32Le(0x08, LeaperKimFirmwareDigests.crc32(firmware, 0x400))
        return firmware
    }

    private fun genBImage(sizeBytes: Int = 4096): ByteArray {
        val image = ByteArray(sizeBytes)
        image.putUInt32Le(0, 0x20005000)
        image.putUInt32Le(4, 0x08004205)
        for (index in 2 until 62) image.putUInt32Le(index * 4, 0x0800423F)
        if (sizeBytes >= 4096) {
            val text = "SysEdition:006.0.10".encodeToByteArray()
            text.copyInto(image, destinationOffset = sizeBytes - text.size - 1)
        }
        return image
    }

    private fun manifest(knownSha: String? = null): LeaperKimFirmwareManifest {
        val builds = knownSha?.let {
            """"$it": {"model": "sherman_l", "family": "gen-b", "edition": "006.0.10", "size": 4096, "format": "raw"}"""
        }.orEmpty()
        return LeaperKimFirmwareManifest.parse(
            """
            {
              "families": [
                {"id": "gen-b", "chip": "gd32f103", "core": "cortex-m3",
                 "resetPc": ["0x08004200", "0x080042FF"],
                 "initialSp": ["0x20004000", "0x20005000"],
                 "vfpMin": 0, "loadBase": "0x08004000", "vtor": "0x08004000"}
              ],
              "models": {"sherman_l": {"family": "gen-b", "hardwareCode": "0060"}},
              "builds": {$builds}
            }
            """.trimIndent(),
        )
    }
}

internal fun ByteArray.putUInt32Le(offset: Int, value: Long) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}
