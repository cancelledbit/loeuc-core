package pw.vasilevskiy.loeuc.shared.protocol

/** One answer to a direct BMS read, as the diagnostics report lists it. */
class InmotionDiagnosticsReportDirectRead(
    val sourceAddress: Int,
    val targetAddress: Int,
    val selector: Int,
    /** When the answer arrived; null where the platform keeps no per-frame clock. */
    val timestampMillis: Long?,
    val payload: ByteArray,
    val rawFrame: ByteArray,
)

/**
 * The plain-text diagnostics report a rider sends to support: the realtime-error answer with
 * its decoded flags, the `0x05` battery slots with every state and fault bit spelled out, and
 * the raw frames behind both. One builder for both platforms, so a report reads the same
 * whichever phone produced it.
 *
 * The report is for a person reading a protocol, so it is English only and carries the raw
 * wire words (`voltageRaw`, hundredths of a volt) rather than display units.
 */
object InmotionDiagnosticsReport {
    /** `timestamp` is the platform's own `yyyyMMdd_HHmmss` rendering of local time. */
    fun fileName(deviceAddress: String, timestamp: String): String {
        val safeAddress = deviceAddress.replace(":", "").replace("-", "")
        return "loeuc_${safeAddress}_p6_diagnostics_$timestamp.txt"
    }

    fun text(
        deviceName: String,
        deviceAddress: String,
        timestampMillis: Long,
        command: Int,
        errorCode: Long?,
        payload: ByteArray,
        rawFrame: ByteArray,
        items: List<WheelDiagnosticItem>,
        batteryPayload: ByteArray?,
        batteryRawFrame: ByteArray?,
        directReads: List<InmotionDiagnosticsReportDirectRead>,
    ): String = buildString {
        val active = items.filter { it.isActive }
        val batteries = batteryPayload
            ?.let { InmotionProtocolEngine().decodeBatteryDiagnostics(it) }
            .orEmpty()

        appendLine("LoEUC Inmotion P6 diagnostics")
        appendLine("Device: $deviceName")
        appendLine("Address: $deviceAddress")
        appendLine("Timestamp: $timestampMillis")
        appendLine("Command: 0x${command.hexByte()}")
        appendLine(
            "General error code: " +
                (errorCode?.let { "0x${it.toString(16).uppercase().padStart(8, '0')} ($it)" } ?: "unavailable"),
        )
        appendLine("Payload length: ${payload.size}")
        appendLine()
        appendLine("Decoded active items (${active.size}), APK-derived V14/P6 mapping:")
        if (active.isEmpty()) {
            appendLine("- none")
        } else {
            active.forEach { item ->
                appendLine(
                    "- [${item.severity.name}] ${item.categoryEn}: ${item.titleEn} " +
                        "(field=${item.index}, payload@${item.payloadOffset}, bit=${item.bitOffset}, raw=${item.rawValue})",
                )
            }
        }
        appendLine()
        appendLine("All decoded fields:")
        items.forEach { item ->
            appendLine(
                "- field=${item.index} raw=${item.rawValue} ${item.categoryEn}: ${item.titleEn} " +
                    "(payload@${item.payloadOffset}, bit=${item.bitOffset})",
            )
        }
        appendLine()
        appendLine("Battery slots (${batteries.size}), command 0x05:")
        if (batteries.isEmpty()) {
            appendLine("- no battery response captured")
        } else {
            batteries.forEach { battery -> appendLine(battery.reportLine()) }
        }
        appendLine()
        appendLine("Payload hex:")
        appendLine(payload.spacedHex())
        appendLine()
        appendLine("Full frame hex:")
        appendLine(rawFrame.spacedHex())
        appendLine()
        appendLine("Battery payload hex:")
        appendLine(batteryPayload?.spacedHex() ?: "unavailable")
        appendLine()
        appendLine("Battery full frame hex:")
        appendLine(batteryRawFrame?.spacedHex() ?: "unavailable")
        appendLine()
        appendLine("Direct BMS read responses (${directReads.size}):")
        if (directReads.isEmpty()) {
            appendLine("- no direct BMS responses captured")
        } else {
            directReads.forEach { response ->
                appendLine(
                    "- source=0x${response.sourceAddress.hexByte()} " +
                        "target=0x${response.targetAddress.hexByte()} selector=${response.selector} " +
                        "timestamp=${response.timestampMillis ?: "unavailable"} " +
                        "payload=${response.payload.spacedHex()}",
                )
                appendLine("  frame=${response.rawFrame.spacedHex()}")
            }
        }
    }

    /**
     * One battery slot. The slot's flag word sits in bytes 6-7: byte 6 carries the state bits
     * and the logic/signal errors, byte 7 the six fault bits.
     */
    private fun InmotionBmsBattery.reportLine(): String {
        val state = rawData.getOrNull(6)?.toInt()?.and(0xFF) ?: 0
        val faults = rawData.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
        val errorLogic = state and 0x40 != 0
        val errorSignal = state and 0x80 != 0
        val voltageSensor = faults and 0x01 != 0
        val currentSensor = faults and 0x02 != 0
        val cannotCharge = faults and 0x04 != 0
        val warning = faults and 0x08 != 0
        val protection = faults and 0x10 != 0
        val error = faults and 0x20 != 0
        val fault = errorLogic || errorSignal || voltageSensor || currentSensor ||
            cannotCharge || warning || protection || error
        return "- battery=$index detected=$detected enabled=$enabled " +
            "charging=$charging voltageRaw=${rawData.u16le(0)} " +
            "chargeCurrentRaw=${rawData.i16le(2)} " +
            "dischargeCurrentRaw=${rawData.i16le(4)} fault=$fault " +
            "logic=$errorLogic signal=$errorSignal " +
            "voltageSensor=$voltageSensor currentSensor=$currentSensor " +
            "cannotCharge=$cannotCharge warning=$warning " +
            "protection=$protection error=$error " +
            "raw=${rawData.spacedHex()}"
    }

    private fun Int.hexByte(): String = toString(16).uppercase().padStart(2, '0')

    private fun ByteArray.spacedHex(): String =
        joinToString(separator = " ") { byte -> (byte.toInt() and 0xFF).hexByte() }

    private fun ByteArray.u16le(offset: Int): Int {
        if (offset + 1 >= size) return 0
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.i16le(offset: Int): Int {
        val value = u16le(offset)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }
}
