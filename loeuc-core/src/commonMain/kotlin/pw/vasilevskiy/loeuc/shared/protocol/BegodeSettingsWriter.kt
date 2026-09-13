package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/**
 * One write in a Begode settings exchange: the byte, and the silence that has to follow it.
 *
 * The pause is part of the command, not politeness towards the radio. Begode has no
 * multi-byte settings frames at all: "set tilt-back to 35" is four separate one-byte writes
 * spaced 50-100 ms apart. Glue them into one array and the wheel assembles a different
 * command, because it tracks state rather than frame length.
 */
data class BegodeWriteStep(
    val byte: Byte,
    val pauseAfterMillis: Long,
)

/**
 * Builds the byte sequences that change a Begode's settings.
 *
 * ## Where this comes from
 *
 * Two independent readings of the same protocol, taken from different sources by people who
 * could not see each other's work: the official Begode app and the Begode branch of EUC
 * World. Only what both readings agree on is built here. What one reading saw alone, or
 * what the two disagreed about, is listed in `docs/begode-write-commands.md` and is not
 * built. The split is deliberate: a settings screen that sends a half-understood byte to a
 * wheel someone is standing on is worse than a settings screen that admits it does not know.
 *
 * ## What is deliberately absent
 *
 * **Gyroscope calibration is not here and never will be.** Its bytes are documented so they
 * can be recognised, never sent: the command uncouples the motor and needs a manual
 * power-off procedure to finish. Sending it to a rider's wheel is a fall. The same goes for
 * the bootloader entry sequence.
 *
 * ## No acknowledgement
 *
 * The wheel never confirms a write. The only way to know a setting took is to read it back
 * out of the ordinary telemetry stream, which is why [BegodeProtocolEngine] now keeps the
 * tilt-back speed, the auto power-off timer, the unit flag and the light mode that frame
 * `type=4 page=24` has been carrying all along.
 */
@ExperimentalWriteApi
object BegodeSettingsWriter {

    /** The wheel is about to receive a parameter. Every numeric setting starts with it. */
    private const val ParameterPrefix: Byte = 'W'.code.toByte()

    /** Spacing both readings observed between the bytes of one command. */
    private const val StepPauseMillis = 100L

    // Setting letters, as both readings have them.
    private const val FieldWeakeningLetter: Byte = 'C'.code.toByte()
    private const val SideTiltAngleLetter: Byte = 'X'.code.toByte()
    private const val PedalDipLetter: Byte = 'R'.code.toByte()
    private const val PedalTiltLetter: Byte = 'U'.code.toByte()
    private const val TiltbackSpeedLetter: Byte = 'Y'.code.toByte()
    private const val PowerMarginLetter: Byte = 'P'.code.toByte()
    private const val BuzzerVolumeLetter: Byte = 'B'.code.toByte()
    private const val LedModeLetter: Byte = 'M'.code.toByte()

    /**
     * The horn. Also the receipt for a settings write - see [beep].
     *
     * Two independent readings have it: the official Begode app, where it is the horn
     * button, and WheelLog, whose `GotwayAdapter.wheelBeep()` sends this byte alone and
     * whose every settings write is followed by it 100 ms later.
     */
    private const val BeepLetter: Byte = 'b'.code.toByte()

    /**
     * Speed tilt-back, km/h, or `null` to switch it off.
     *
     * **Always two ASCII digits**, leading zero included: both readings are explicit that a
     * single digit is not accepted. The off switch is its own byte and carries no value.
     */
    fun tiltbackSpeed(speedKmh: Int?): List<BegodeWriteStep> {
        if (speedKmh == null) return single('"')
        val clamped = speedKmh.coerceIn(1, 99)
        return parameter(TiltbackSpeedLetter, twoDigits(clamped))
    }

    /**
     * Power margin the wheel starts warning at, in percent of the reserve left.
     *
     * The wire carries the **load level**, and the margin is its complement: a rider asking
     * to be warned with 30 % left puts 70 on the wire. The two source apps show the two ends
     * of the same number, one the level and one the margin, which is what made this look like
     * a disagreement between them at first. We show the margin: "30 % of reserve left" needs
     * no explaining, "load 70" does.
     */
    fun powerMarginPercent(marginPercent: Int): List<BegodeWriteStep> {
        val level = (100 - marginPercent).coerceIn(10, 99)
        return parameter(PowerMarginLetter, twoDigits(level))
    }

    /**
     * Field weakening level.
     *
     * The floor is one, not zero: that is exactly where the two readings disagreed, one
     * allowing zero and the other refusing it with an explicit check. The catalogue clamps
     * to the same intersection, and the two floors have to agree - a caller reaching the
     * builder directly must not be able to send the byte one of the source apps refuses.
     */
    fun fieldWeakening(level: Int): List<BegodeWriteStep> =
        parameter(FieldWeakeningLetter, singleDigit(level, min = 1))

    /** Maximum side tilt angle. The wire carries an index; the angle is `45 + 5 * index`. */
    fun sideTiltAngleDegrees(degrees: Int): List<BegodeWriteStep> {
        val index = ((degrees - MinSideTiltDegrees) / SideTiltStepDegrees).coerceIn(0, 9)
        return parameter(SideTiltAngleLetter, singleDigit(index, min = 0))
    }

    fun pedalDip(level: Int): List<BegodeWriteStep> =
        parameter(PedalDipLetter, singleDigit(level, min = 0))

    /**
     * Pedal horizontal trim. The wire carries `0..8`; the rider sees it centred on zero,
     * `-4..+4`, because "level 4" means "level" and "0" means "level" to nobody.
     */
    fun pedalTrim(trim: Int): List<BegodeWriteStep> =
        parameter(PedalTiltLetter, singleDigit(trim + PedalTrimCentre, min = 0, max = 8))

    fun buzzerVolume(level: Int): List<BegodeWriteStep> =
        parameter(BuzzerVolumeLetter, singleDigit(level, min = 1))

    fun ledMode(mode: Int): List<BegodeWriteStep> =
        parameter(LedModeLetter, singleDigit(mode, min = 0))

    /** Ride mode. Single letter, no prefix, no value. */
    fun rideMode(mode: BegodeRideMode): List<BegodeWriteStep> = single(
        when (mode) {
            BegodeRideMode.Soft -> 's'
            BegodeRideMode.Medium -> 'f'
            BegodeRideMode.Hard -> 'h'
        },
    )

    /** Units shown on the wheel's own display. */
    fun useMiles(miles: Boolean): List<BegodeWriteStep> = single(if (miles) 'm' else 'g')

    fun sideTiltThreshold(threshold: BegodeTiltThreshold): List<BegodeWriteStep> = single(
        when (threshold) {
            BegodeTiltThreshold.Low -> '>'
            BegodeTiltThreshold.Medium -> '='
            BegodeTiltThreshold.High -> '<'
        },
    )

    /**
     * Speed alarms.
     *
     * Only two of the three letters are built. The two readings agreed on `o` (both alarms
     * on) and on `u` (the first one off), and **disagreed about `i`**: one read it as "second
     * alarm off", the other as "all alarms off". A rider who thinks the second alarm is still
     * armed and is wrong gets no warning at all, so `i` waits for a live wheel.
     */
    fun speedAlarms(alarms: BegodeSpeedAlarms): List<BegodeWriteStep> = single(
        when (alarms) {
            BegodeSpeedAlarms.Both -> 'o'
            BegodeSpeedAlarms.SecondOnly -> 'u'
        },
    )

    /**
     * PWM tilt-back on Freestyl3r (`CF`) firmware: `I` switches it on, `o` switches it off.
     *
     * Validation: WheelLog + EUC World agree on bytes. `I` is the fourth speed-alarm letter in
     * both - WheelLog's `alarmModeCF` ("PWM tiltback for custom firmware"), EUC World's
     * `Y(3)`, sent when its CF-only "dynamic tiltback" switch goes on. Both read the mode back
     * from `type=4 @6` bits 10..11, where `3` means this mode is on.
     *
     * Off is the stock letter `o`, both speed alarms armed, the one `speedAlarms` already sends.
     * EUC World switches off by re-sending the rider's stored alarm choice, which it defaults to
     * the same `o`; it then re-sends the tilt-back speed a second later, which this does not do -
     * the speed reads back from `type=4 @10`, so a rider sees whether it held.
     *
     * Only offered on `CF` firmware: what `I` does on stock firmware is not established.
     */
    fun pwmTiltback(enabled: Boolean): List<BegodeWriteStep> = single(if (enabled) 'I' else 'o')

    /** Tilt-back when the power margin runs out. */
    fun powerMarginTiltback(enabled: Boolean): List<BegodeWriteStep> = single(if (enabled) 'e' else 'x')

    /**
     * Racing mode is a toggle without an argument: two bytes two seconds apart, and the
     * wheel flips whatever it was on. Both readings agree on the pair and on the gap.
     *
     * A toggle with no way to ask for a specific state is a poor thing to put on a settings
     * screen - the screen cannot promise a result - so the caller has to read the mode back
     * before claiming anything.
     */
    fun toggleRacingMode(): List<BegodeWriteStep> = listOf(
        BegodeWriteStep('+'.code.toByte(), RacingModeGapMillis),
        BegodeWriteStep('-'.code.toByte(), StepPauseMillis),
    )

    /**
     * Make the wheel beep.
     *
     * The only receipt a Begode gives. The brand acknowledges no write at all, so a rider
     * who moved a slider has no way of knowing the wheel heard it - except by hearing it.
     * WheelLog appends this to every settings write for exactly that reason, and riders who
     * came from it expect the sound.
     */
    fun beep(): List<BegodeWriteStep> = single('b')

    /**
     * A settings sequence plus the beep that tells the rider the wheel heard it.
     *
     * The beep keeps the same pause as every other byte. The silence is not politeness: it
     * is what separates one command from the next, and a beep with no silence after it
     * would run into whatever the rider presses second.
     */
    internal fun withBeep(steps: List<BegodeWriteStep>): List<BegodeWriteStep> =
        steps + BegodeWriteStep(BeepLetter, StepPauseMillis)

    private fun parameter(letter: Byte, digits: List<Byte>): List<BegodeWriteStep> {
        val steps = mutableListOf(
            BegodeWriteStep(ParameterPrefix, StepPauseMillis),
            BegodeWriteStep(letter, StepPauseMillis),
        )
        digits.forEach { digit -> steps.add(BegodeWriteStep(digit, StepPauseMillis)) }
        return steps
    }

    /**
     * A one-byte command, with the silence that has to follow it.
     *
     * Every command ends in silence, the last byte included. The wheel has no terminator and
     * no length field: the only thing separating one command from the next is the gap between
     * them. A trailing zero would let a second setting start as little as one radio interval
     * later, and `W Y 3 5` followed by `W B 5` would arrive as `W W Y B 3 5 5` - which is a
     * command, just not either of the two that were sent.
     */
    private fun single(symbol: Char): List<BegodeWriteStep> =
        listOf(BegodeWriteStep(symbol.code.toByte(), StepPauseMillis))

    private fun singleDigit(value: Int, min: Int, max: Int = 9): List<Byte> =
        listOf(asciiDigit(value.coerceIn(min, max)))

    private fun twoDigits(value: Int): List<Byte> =
        listOf(asciiDigit(value / 10), asciiDigit(value % 10))

    private fun asciiDigit(digit: Int): Byte = ('0'.code + digit.coerceIn(0, 9)).toByte()

    /** The rider's zero sits in the middle of the wire's `0..8`. */
    const val PedalTrimCentre = 4

    const val MinSideTiltDegrees = 45
    const val SideTiltStepDegrees = 5

    /** Both readings observed the same two seconds between the halves of the racing toggle. */
    private const val RacingModeGapMillis = 2_000L
}

enum class BegodeRideMode { Soft, Medium, Hard }

enum class BegodeTiltThreshold { Low, Medium, High }

/**
 * Speed alarm choices this build is willing to send.
 *
 * The third letter the source apps have (`i`) is missing on purpose: the two readings
 * disagreed about whether it silences the second alarm or all of them.
 */
enum class BegodeSpeedAlarms { Both, SecondOnly }
