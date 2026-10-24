package pw.vasilevskiy.loeuc.extension

import android.os.Parcel
import android.os.Parcelable

/*
 * Every parcel here is written as a length followed by its fields, and read back up to that
 * length and no further. That is what lets the contract grow: a newer host appends a field,
 * an older extension reads the fields it knows and skips the rest, and a newer extension
 * reading an older host's parcel finds the new field absent and takes its default. Fields
 * are only ever appended.
 */

internal inline fun Parcel.writeSized(block: Parcel.() -> Unit) {
    val sizeAt = dataPosition()
    writeInt(0)
    val start = dataPosition()
    block()
    val end = dataPosition()
    setDataPosition(sizeAt)
    writeInt(end - start)
    setDataPosition(end)
}

internal inline fun <T> Parcel.readSized(block: SizedReader.() -> T): T {
    val size = readInt()
    val start = dataPosition()
    val end = start + size
    val value = SizedReader(this, end).block()
    setDataPosition(end)
    return value
}

/** Reads the fields of one sized parcel; a field past the written end reads as its default. */
internal class SizedReader(private val parcel: Parcel, private val end: Int) {
    private val more: Boolean get() = parcel.dataPosition() < end

    fun int(default: Int = 0): Int = if (more) parcel.readInt() else default
    fun long(default: Long = 0L): Long = if (more) parcel.readLong() else default
    fun double(default: Double = Double.NaN): Double = if (more) parcel.readDouble() else default
    fun boolean(default: Boolean = false): Boolean = if (more) parcel.readInt() != 0 else default
    fun string(default: String? = null): String? = if (more) parcel.readString() else default
    fun intArray(): IntArray = if (more) parcel.createIntArray() ?: IntArray(0) else IntArray(0)
    fun doubleArray(): DoubleArray = if (more) parcel.createDoubleArray() ?: DoubleArray(0) else DoubleArray(0)
    fun strings(): List<String> = if (more) parcel.createStringArrayList().orEmpty() else emptyList()
    fun bytes(): ByteArray = if (more) parcel.createByteArray() ?: ByteArray(0) else ByteArray(0)
}

private fun Parcel.writeBool(value: Boolean) = writeInt(if (value) 1 else 0)

private inline fun <T> creator(
    crossinline read: (Parcel) -> T,
    crossinline array: (Int) -> Array<T?>,
): Parcelable.Creator<T> =
    object : Parcelable.Creator<T> {
        override fun createFromParcel(source: Parcel): T = read(source)
        override fun newArray(size: Int): Array<T?> = array(size)
    }

/** The device a session is about, as far as the host is willing to describe it. */
class DeviceDescriptor(
    /** The name the device advertises. */
    val name: String?,
    /** Model as the host identified it, in the host's own words; free text, not an enum. */
    val model: String?,
    val firmwareVersion: String?,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized {
        writeString(name)
        writeString(model)
        writeString(firmwareVersion)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<DeviceDescriptor> = creator(
            { parcel -> parcel.readSized { DeviceDescriptor(string(), string(), string()) } },
            { size -> arrayOfNulls(size) },
        )
    }
}

/**
 * One telemetry snapshot. Metrics are parallel arrays of [ExtensionMetric] codes and values;
 * a metric the device does not report is absent rather than zero.
 */
class TelemetrySnapshot(
    /** Time of the snapshot, ms. In playback this is the recording's clock, not the phone's. */
    val timestampMillis: Long,
    /** One of [TelemetryMode]. */
    val mode: Int,
    val codes: IntArray,
    val values: DoubleArray,
    /**
     * Position, present only when the manifest asks for it and the rider allowed it. Without
     * that permission the host leaves it out of the parcel altogether.
     */
    val latitude: Double?,
    val longitude: Double?,
) : Parcelable {
    init {
        require(codes.size == values.size) { "codes and values differ in length" }
    }

    val isPlayback: Boolean get() = mode == TelemetryMode.PLAYBACK

    /** The value of one [ExtensionMetric], or `null` when the snapshot does not carry it. */
    fun value(metric: Int): Double? {
        val index = codes.indexOf(metric)
        return if (index < 0) null else values[index]
    }

    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized {
        writeLong(timestampMillis)
        writeInt(mode)
        writeIntArray(codes)
        writeDoubleArray(values)
        val hasPosition = latitude != null && longitude != null
        writeBool(hasPosition)
        writeDouble(if (hasPosition) latitude!! else Double.NaN)
        writeDouble(if (hasPosition) longitude!! else Double.NaN)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TelemetrySnapshot> = creator(
            { parcel ->
                parcel.readSized {
                    val timestamp = long()
                    val mode = int()
                    val codes = intArray()
                    val values = doubleArray()
                    val hasPosition = boolean()
                    val latitude = double()
                    val longitude = double()
                    // A malformed pair is dropped rather than trusted: the arrays are read by index.
                    val length = minOf(codes.size, values.size)
                    TelemetrySnapshot(
                        timestampMillis = timestamp,
                        mode = mode,
                        codes = codes.copyOf(length),
                        values = values.copyOf(length),
                        latitude = latitude.takeIf { hasPosition },
                        longitude = longitude.takeIf { hasPosition },
                    )
                }
            },
            { size -> arrayOfNulls(size) },
        )
    }
}

/**
 * A tile reading. [Double.NaN] clears the tile. A value outside the bounds the manifest
 * declares is not shown - the host treats it as foreign bytes, not as a reading.
 */
class TileValue(val value: Double) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized { writeDouble(value) }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TileValue> = creator(
            { parcel -> parcel.readSized { TileValue(double()) } },
            { size -> arrayOfNulls(size) },
        )
    }
}

/**
 * Asks for a declared alert to sound or to stop. [intensity] in 0..1 tells a ramping alert
 * how far along it is; the host decides what that sounds like.
 */
class AlertRequest(val active: Boolean, val intensity: Double = 1.0) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized {
        writeBool(active)
        writeDouble(intensity)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AlertRequest> = creator(
            { parcel -> parcel.readSized { AlertRequest(boolean(), double(1.0)) } },
            { size -> arrayOfNulls(size) },
        )
    }
}

/**
 * The value a command is sent with. [value] is `null` for a command without a parameter.
 * The extension picks [requestId]; the matching [CommandResult] carries it back.
 */
class CommandValue(val requestId: Int, val value: Double?) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized {
        writeInt(requestId)
        writeBool(value != null)
        writeDouble(value ?: Double.NaN)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CommandValue> = creator(
            { parcel ->
                parcel.readSized {
                    val id = int()
                    val hasValue = boolean()
                    val value = double()
                    CommandValue(id, value.takeIf { hasValue })
                }
            },
            { size -> arrayOfNulls(size) },
        )
    }
}

/** How a command ended: [outcome] is [RefusalReason.SENT] or the reason it was not sent. */
class CommandResult(
    val requestId: Int,
    val key: String,
    val outcome: Int,
    /** The bytes the host assembled, for the extension's own log. Empty when nothing was built. */
    val bytes: ByteArray,
) : Parcelable {
    val sent: Boolean get() = outcome == RefusalReason.SENT

    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized {
        writeInt(requestId)
        writeString(key)
        writeInt(outcome)
        writeByteArray(bytes)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CommandResult> = creator(
            { parcel ->
                parcel.readSized {
                    val id = int()
                    val key = string().orEmpty()
                    val outcome = int(RefusalReason.NOT_PERMITTED)
                    CommandResult(id, key, outcome, bytes())
                }
            },
            { size -> arrayOfNulls(size) },
        )

    }
}

/** What the host can do for the bound extension right now. */
class HostCapabilities(
    val apiVersion: Int,
    val maxTelemetryHz: Int,
    val connected: Boolean,
    /** Keys of declared commands the connected device can execute; the rest would be refused. */
    val availableCommands: List<String>,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeSized {
        writeInt(apiVersion)
        writeInt(maxTelemetryHz)
        writeBool(connected)
        writeStringList(availableCommands)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HostCapabilities> = creator(
            { parcel ->
                parcel.readSized {
                    HostCapabilities(int(), int(ExtensionApi.MAX_TELEMETRY_HZ), boolean(), strings())
                }
            },
            { size -> arrayOfNulls(size) },
        )
    }
}
