package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Begode settings commands. Two independent readings of the protocol agree on every
 * sequence checked here; `docs/begode-write-commands.md` records what they disagreed about
 * and what is therefore not built at all.
 */
@OptIn(ExperimentalWriteApi::class)
class BegodeSettingsWriterTest {

    private fun ascii(steps: List<BegodeWriteStep>): String =
        steps.map { it.byte.toInt().toChar() }.joinToString("")

    /**
     * A numeric setting is a conversation, not a packet: prefix, letter, digits, each its
     * own write, spaced apart. Glue them together and the wheel assembles something else.
     */
    @Test
    fun aNumericSettingIsSeparateWritesWithPauses() {
        val steps = BegodeSettingsWriter.buzzerVolume(5)

        assertEquals("WB5", ascii(steps))
        assertTrue(
            steps.all { it.pauseAfterMillis > 0 },
            "every byte needs a pause after it, the last one included: the silence after a command is " +
                "the only thing separating it from the next",
        )
    }

    /**
     * Tilt-back speed is always two digits, leading zero included. Both readings are
     * explicit that a single digit is not accepted.
     */
    @Test
    fun tiltbackSpeedAlwaysCarriesTwoDigits() {
        assertEquals("WY35", ascii(BegodeSettingsWriter.tiltbackSpeed(35)))
        assertEquals("WY09", ascii(BegodeSettingsWriter.tiltbackSpeed(9)))
    }

    /** Switching tilt-back off is its own byte and carries no value. */
    @Test
    fun tiltbackOffIsItsOwnByte() {
        assertEquals("\"", ascii(BegodeSettingsWriter.tiltbackSpeed(null)))
    }

    /**
     * The rider asks for the reserve left; the wire carries the load level. A request to be
     * warned with 30 % left puts 70 on the wire.
     */
    @Test
    fun powerMarginIsWrittenAsItsComplement() {
        assertEquals("WP70", ascii(BegodeSettingsWriter.powerMarginPercent(30)))
        assertEquals("WP80", ascii(BegodeSettingsWriter.powerMarginPercent(20)))
    }

    /** The wire carries an index into 45°..90°, not the angle. */
    @Test
    fun sideTiltAngleGoesOutAsAnIndex() {
        assertEquals("WX0", ascii(BegodeSettingsWriter.sideTiltAngleDegrees(45)))
        assertEquals("WX9", ascii(BegodeSettingsWriter.sideTiltAngleDegrees(90)))
        assertEquals("WX4", ascii(BegodeSettingsWriter.sideTiltAngleDegrees(65)))
    }

    /** The rider's zero sits in the middle of the wire's `0..8`. */
    @Test
    fun pedalTrimIsCentredOnZero() {
        assertEquals("WU4", ascii(BegodeSettingsWriter.pedalTrim(0)))
        assertEquals("WU0", ascii(BegodeSettingsWriter.pedalTrim(-4)))
        assertEquals("WU8", ascii(BegodeSettingsWriter.pedalTrim(4)))
    }

    /** Modes and switches are one letter with no prefix and no value. */
    @Test
    fun modesAreASingleLetterWithoutThePrefix() {
        assertEquals("s", ascii(BegodeSettingsWriter.rideMode(BegodeRideMode.Soft)))
        assertEquals("f", ascii(BegodeSettingsWriter.rideMode(BegodeRideMode.Medium)))
        assertEquals("h", ascii(BegodeSettingsWriter.rideMode(BegodeRideMode.Hard)))
        assertEquals("m", ascii(BegodeSettingsWriter.useMiles(true)))
        assertEquals("g", ascii(BegodeSettingsWriter.useMiles(false)))
        assertEquals("e", ascii(BegodeSettingsWriter.powerMarginTiltback(true)))
        assertEquals("x", ascii(BegodeSettingsWriter.powerMarginTiltback(false)))
    }

    /**
     * The racing toggle is a pair two seconds apart. The gap is the command, not politeness:
     * both readings observed the same two seconds.
     */
    @Test
    fun theRacingToggleKeepsItsGap() {
        val steps = BegodeSettingsWriter.toggleRacingMode()

        assertEquals("+-", ascii(steps))
        assertEquals(2_000L, steps.first().pauseAfterMillis)
    }

    /**
     * Nothing here ever produces the gyroscope calibration or the bootloader entry. They are
     * documented so they can be recognised, never sent.
     */
    @Test
    fun noCommandEverProducesTheForbiddenBytes() {
        val forbidden = setOf('c', 'y', '!', '@')
        val everything = buildList {
            addAll(BegodeSettingsWriter.tiltbackSpeed(35))
            addAll(BegodeSettingsWriter.tiltbackSpeed(null))
            addAll(BegodeSettingsWriter.powerMarginPercent(30))
            addAll(BegodeSettingsWriter.fieldWeakening(5))
            addAll(BegodeSettingsWriter.sideTiltAngleDegrees(70))
            addAll(BegodeSettingsWriter.pedalDip(3))
            addAll(BegodeSettingsWriter.pedalTrim(0))
            addAll(BegodeSettingsWriter.buzzerVolume(5))
            addAll(BegodeSettingsWriter.ledMode(4))
            addAll(BegodeSettingsWriter.toggleRacingMode())
            BegodeRideMode.entries.forEach { addAll(BegodeSettingsWriter.rideMode(it)) }
            BegodeTiltThreshold.entries.forEach { addAll(BegodeSettingsWriter.sideTiltThreshold(it)) }
            BegodeSpeedAlarms.entries.forEach { addAll(BegodeSettingsWriter.speedAlarms(it)) }
            addAll(BegodeSettingsWriter.useMiles(true))
            addAll(BegodeSettingsWriter.powerMarginTiltback(true))
            addAll(BegodeSettingsWriter.pwmTiltback(true))
            addAll(BegodeSettingsWriter.pwmTiltback(false))
        }

        everything.forEach { step ->
            val symbol = step.byte.toInt().toChar()
            assertTrue(symbol !in forbidden, "a command assembled the forbidden byte '$symbol'")
        }
    }
}

/**
 * The catalogue itself: what the screen is handed, and what it is deliberately not.
 */
@OptIn(ExperimentalWriteApi::class)
class BegodeSettingsCatalogTest {

    private val reportingWheel = BegodeTelemetry(
        tiltbackSpeedKmh = 35.0,
        autoPowerOffSeconds = 7_200.0,
        usesMiles = false,
        rideMode = 1.0,
    )

    /** Every row lands in a real section: a key nobody placed falls into "Other" silently. */
    @Test
    fun everySettingHasItsSection() {
        BegodeSettingsCatalog.capabilities(reportingWheel).forEach { capability ->
            val group = WheelSettingGroups.of(capability.key)
            assertTrue(
                group != WheelSettingGroups.Other && group != WheelSettingGroups.Raw,
                "'${capability.key}' was left without a section and fell into '${group.titleRu}'",
            )
        }
    }

    /** Every writable row maps to bytes, and every read-only row honestly maps to none. */
    @Test
    fun writableRowsProduceBytesAndReadOnlyOnesDoNot() {
        BegodeSettingsCatalog.capabilities(reportingWheel).forEach { capability ->
            val steps = BegodeSettingsCatalog.writeSteps(capability.key, capability.minValue)
            if (capability.commandId == null) {
                assertTrue(steps == null, "'${capability.key}' is read-only, yet bytes were produced")
            } else {
                assertTrue(steps != null, "'${capability.key}' is writable, yet there are no bytes for it")
            }
        }
    }

    /**
     * The settings the wheel has reported come back with a value; the rest stay empty.
     *
     * Empty is the honest answer, not a gap: Begode confirms nothing it is told, and filling
     * these with the last value written would show the rider their own intent dressed up as
     * the wheel's state.
     */
    @Test
    fun onlyTheReportedSettingsCarryAValue() {
        val byKey = BegodeSettingsCatalog.capabilities(reportingWheel).associateBy { it.key }

        assertEquals(35, byKey[BegodeSettingsCatalog.TiltbackSpeedKey]?.rawValue)
        assertEquals(7_200, byKey[BegodeSettingsCatalog.AutoPowerOffKey]?.rawValue)
        assertEquals(0, byKey[BegodeSettingsCatalog.UnitsKey]?.rawValue)
        assertEquals(1, byKey[BegodeSettingsCatalog.RideModeKey]?.rawValue)
        assertEquals(null, byKey[BegodeSettingsCatalog.FieldWeakeningKey]?.rawValue)
        assertEquals(null, byKey[BegodeSettingsCatalog.BuzzerVolumeKey]?.rawValue)
        assertEquals(null, byKey[BegodeSettingsCatalog.PowerMarginTiltbackKey]?.rawValue)
    }

    /** Each read-back lands on its row in the row's own units. */
    @Test
    fun theReadBackSettingsFillTheirRows() {
        val wheel = BegodeTelemetry(
            sideTiltThreshold = 2,
            sideTiltAngleIndex = 3,
            fieldWeakening = 6,
            pedalDip = 4,
            pedalTrim = -2,
            buzzerVolume = 9,
            ledMode = 1,
            speedAlarmMode = 0,
        )
        val byKey = BegodeSettingsCatalog.capabilities(wheel).associateBy { it.key }

        assertEquals(2, byKey[BegodeSettingsCatalog.SideTiltThresholdKey]?.rawValue)
        assertEquals(60, byKey[BegodeSettingsCatalog.SideTiltAngleKey]?.rawValue, "45 + 3 * 5 degrees")
        assertEquals(6, byKey[BegodeSettingsCatalog.FieldWeakeningKey]?.rawValue)
        assertEquals(4, byKey[BegodeSettingsCatalog.PedalDipKey]?.rawValue)
        assertEquals(-2, byKey[BegodeSettingsCatalog.PedalTrimKey]?.rawValue)
        assertEquals(9, byKey[BegodeSettingsCatalog.BuzzerVolumeKey]?.rawValue)
        assertEquals(1, byKey[BegodeSettingsCatalog.LedModeKey]?.rawValue)
        assertEquals(1, byKey[BegodeSettingsCatalog.SpeedAlarmsKey]?.rawValue, "wire 0 is `o`, the switch on")
    }

    /**
     * A reading the row cannot write back stays unknown.
     *
     * A profile captures what the row shows and applies it later. Field weakening `0` shown on
     * a `1..9` slider would be captured as `0` and written as `1`; alarm mode `2` is the `i`
     * letter this catalogue does not send at all.
     */
    @Test
    fun aReadingTheRowCannotWriteBackIsNotShown() {
        val wheel = BegodeTelemetry(fieldWeakening = 0, pedalTrim = 5, speedAlarmMode = 2)
        val byKey = BegodeSettingsCatalog.capabilities(wheel).associateBy { it.key }

        assertNull(byKey[BegodeSettingsCatalog.FieldWeakeningKey]?.rawValue)
        assertNull(byKey[BegodeSettingsCatalog.PedalTrimKey]?.rawValue)
        assertNull(byKey[BegodeSettingsCatalog.SpeedAlarmsKey]?.rawValue)
    }

    /**
     * The control kinds are the four shared strings, spelled the way both screens compare them.
     *
     * Not a style rule: the iOS screen switches on this string literally, so a capitalised
     * "Toggle" draws a slider there and nobody notices until a rider drags a two-position
     * switch across a range.
     */
    @Test
    fun kindsUseTheSharedSpelling() {
        val allowed = setOf("slider", "toggle", "unit", "choice", KIND_READONLY)

        BegodeSettingsCatalog.capabilities(reportingWheel).forEach { capability ->
            assertTrue(
                capability.kind in allowed,
                "'${capability.key}' declared the kind '${capability.kind}', which the screens do not know",
            )
        }
    }

    /** The one row nobody can write says so in the field both screens read. */
    @Test
    fun theReadOnlyRowIsMarkedReadOnly() {
        val autoPowerOff = BegodeSettingsCatalog.capabilities(reportingWheel)
            .first { it.key == BegodeSettingsCatalog.AutoPowerOffKey }

        assertEquals(KIND_READONLY, autoPowerOff.kind)
        assertEquals(null, autoPowerOff.commandId)
    }

    /**
     * The first-alarm switch sends the letter its label promises.
     *
     * On, both alarms are armed; off, only the second one is. A two-position row that meant
     * the opposite would silence an alarm for a rider who thought they were enabling one.
     */
    @Test
    fun theFirstAlarmSwitchIsNotInverted() {
        // The switch itself comes first, the acknowledging beep second; see
        // everySettingWriteEndsWithTheBeepThatTellsTheRiderItLanded.
        fun letter(value: Int) = BegodeSettingsCatalog
            .writeSteps(BegodeSettingsCatalog.SpeedAlarmsKey, value)!!
            .first().byte.toInt().toChar()

        assertEquals('o', letter(1))
        assertEquals('u', letter(0))
    }

    /**
     * A wheel that has told us nothing yet reports no tilt-back rather than a made-up one.
     *
     * "Off" and "has not said yet" must not be the same value. They were, and it cost twice:
     * the card printed "On the wheel: Off" for a wheel that had sent no `type=4 page=24`
     * frame at all, and `capturePresetSettings` took that for a reading - so a profile
     * captured in the first seconds of a connection switched the rider's tilt-back off when
     * it was applied.
     */
    @Test
    fun aSilentWheelDoesNotInventATiltback() {
        val silent = BegodeTelemetry()

        val tiltback = BegodeSettingsCatalog.capabilities(silent)
            .first { it.key == BegodeSettingsCatalog.TiltbackSpeedKey }

        assertNull(tiltback.rawValue)
    }

    /** A wheel that has actually said "off" does report the off value. */
    @Test
    fun aWheelThatSaysOffReportsTheOffValue() {
        val off = BegodeTelemetry(tiltbackSpeedKmh = 0.0)

        val tiltback = BegodeSettingsCatalog.capabilities(off)
            .first { it.key == BegodeSettingsCatalog.TiltbackSpeedKey }

        assertEquals(BegodeSettingsCatalog.TiltbackOffValue, tiltback.rawValue)
    }

    /**
     * A rider reported ride mode and the side tilt threshold drawn as **km / mi** buttons.
     * They were declared "unit", which is not a placeholder for "some choice": it literally
     * means kilometres or miles, and both screens draw exactly those two buttons for it. Two
     * buttons also write only 0 and 1, so the third value of a three-way setting could not be
     * reached at all.
     */
    @Test
    fun theUnitKindBelongsOnlyToTheSettingThatIsActuallyKilometresOrMiles() {
        val unitKeys = BegodeSettingsCatalog.capabilities(reportingWheel)
            .filter { it.kind == "unit" }
            .map { it.key }

        assertEquals(listOf(BegodeSettingsCatalog.UnitsKey), unitKeys)
    }

    @Test
    fun rideModeAndTheTiltThresholdAreChoicesOfThreeNamedValues() {
        val capabilities = BegodeSettingsCatalog.capabilities(reportingWheel)
        val rideMode = capabilities.single { it.key == BegodeSettingsCatalog.RideModeKey }
        val threshold = capabilities.single { it.key == BegodeSettingsCatalog.SideTiltThresholdKey }

        assertEquals("choice", rideMode.kind)
        assertEquals(listOf("Soft", "Medium", "Hard"), rideMode.options.map { it.titleEn })
        assertEquals("choice", threshold.kind)
        assertEquals(listOf("Low", "Medium", "High"), threshold.options.map { it.titleEn })
    }

    /**
     * Every value a choice offers must be reachable and must send bytes of its own. This is
     * the half of the bug that was not cosmetic: with two buttons, "Hard" was unreachable.
     */
    @Test
    fun everyChoiceValueWritesItsOwnCommand() {
        BegodeSettingsCatalog.capabilities(reportingWheel)
            .filter { it.kind == "choice" }
            .forEach { capability ->
                val written = capability.options.map { option ->
                    BegodeSettingsCatalog.writeSteps(capability.key, option.value)
                }
                assertTrue(written.all { it != null }, "${capability.key}: a value writes nothing")
                assertEquals(
                    written.size,
                    written.distinct().size,
                    "${capability.key}: two values send the same bytes",
                )
            }
    }

    @Test
    fun aChoiceCoversExactlyTheRangeItDeclares() {
        BegodeSettingsCatalog.capabilities(reportingWheel)
            .filter { it.kind == "choice" }
            .forEach { capability ->
                assertEquals(
                    (capability.minValue..capability.maxValue).toList(),
                    capability.options.map { it.value },
                    "${capability.key}: the options and the range disagree",
                )
            }
    }

    /** Options belong to choices only: a slider with named values would draw both controls. */
    @Test
    fun nothingButAChoiceCarriesOptions() {
        val strays = BegodeSettingsCatalog.capabilities(reportingWheel)
            .filter { it.kind != "choice" && it.options.isNotEmpty() }

        assertTrue(strays.isEmpty(), "options on a non-choice: ${strays.map { it.key }}")
    }

    /**
     * Every settings write ends with the beep.
     *
     * A Begode acknowledges nothing at all, so the sound is the only receipt a rider gets:
     * the slider moved, the wheel beeped, the setting landed. WheelLog has done this from
     * the start — its default `sendCommand` appends `b` 100 ms after the command — and
     * riders coming from it report a silent wheel as a broken write.
     */
    @Test
    fun everySettingWriteEndsWithTheBeepThatTellsTheRiderItLanded() {
        val keys = BegodeSettingsCatalog.capabilities(reportingWheel)
            .filter { it.commandId != null }
            .map { it.key }

        for (key in keys) {
            val steps = BegodeSettingsCatalog.writeSteps(key, 1)
            assertTrue(steps != null && steps.isNotEmpty(), "$key: does not write at all")
            assertEquals(
                'b'.code.toByte(),
                steps!!.last().byte,
                "$key: the last byte is not the beep",
            )
            assertTrue(steps.size >= 2, "$key: the beep is there, the command itself is not")
        }
    }

    /**
     * No step anywhere ends in a zero pause.
     *
     * The gap is the only thing separating one command from the next, and a zero at the end
     * of a sequence lets the following one start a radio interval later - two commands
     * arriving as a third. Tilt-back off used to carry exactly that zero, and on iOS both
     * its byte and the beep were scheduled for the same instant.
     */
    @Test
    fun noStepEverEndsInSilenceOfZeroLength() {
        val sequences = BegodeSettingsCatalog.capabilities(reportingWheel)
            .filter { it.commandId != null }
            .mapNotNull { capability ->
                BegodeSettingsCatalog.writeSteps(capability.key, 1)?.let { capability.key to it }
            } + listOf(
            "tiltback_off" to BegodeSettingsWriter.tiltbackSpeed(null),
            "tiltback_off_with_beep" to
                BegodeSettingsWriter.withBeep(BegodeSettingsWriter.tiltbackSpeed(null)),
            "racing_toggle" to BegodeSettingsWriter.toggleRacingMode(),
            "beep" to BegodeSettingsWriter.beep(),
        )

        for ((key, steps) in sequences) {
            assertTrue(
                steps.all { it.pauseAfterMillis > 0 },
                "$key: a zero-length pause - ${steps.map { it.pauseAfterMillis }}",
            )
        }
    }

    /** The beep is the last byte, not an extra one in the middle of a parameter sequence. */
    @Test
    fun theBeepFollowsTheWholeParameterSequenceRatherThanSittingInsideIt() {
        val steps = BegodeSettingsCatalog.writeSteps(BegodeSettingsCatalog.TiltbackSpeedKey, 35)!!

        assertEquals(
            listOf('W', 'Y', '3', '5', 'b'),
            steps.map { it.byte.toInt().toChar() },
        )
    }

    /** The horn on its own is the same byte, which is what makes it a receipt a rider hears. */
    @Test
    fun theHornAndTheReceiptAreTheSameByte() {
        assertEquals('b'.code.toByte(), BegodeSettingsWriter.beep().single().byte)
    }

    /**
     * The one-frame horn is exactly the beep byte, alone - what the app's horn button built
     * out of [BegodeSettingsWriter.beep] before the frame form existed.
     */
    @Test
    fun theHornFrameIsTheBeepByteAlone() {
        val horn = BegodeSettingsWriter.horn()

        assertEquals(listOf(0x62.toByte()), horn.toList())
        assertEquals(BegodeSettingsWriter.beep().map { it.byte }, horn.toList())
    }
}
