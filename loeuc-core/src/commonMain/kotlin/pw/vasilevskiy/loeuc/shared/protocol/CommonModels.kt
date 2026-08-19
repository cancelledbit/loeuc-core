package pw.vasilevskiy.loeuc.shared.protocol

enum class WheelDiagnosticSeverity {
    Error,
    Warning,
}

data class WheelDiagnosticItem(
    val index: Int,
    val payloadOffset: Int,
    val bitOffset: Int,
    val rawValue: Int,
    val categoryEn: String,
    val categoryRu: String,
    val titleEn: String,
    val titleRu: String,
    val severity: WheelDiagnosticSeverity,
) {
    val isActive: Boolean
        get() = rawValue == 1
}

data class WheelBatteryDiagnostic(
    val index: Int,
    val detected: Boolean,
    val enabled: Boolean,
    val charging: Boolean,
    val voltage: Double,
    val chargeCurrent: Double,
    val dischargeCurrent: Double,
    val hasFault: Boolean,
    val hexData: String,
)

data class WheelDiagnosticsSnapshot(
    val timestampMillis: Long,
    val command: Int,
    val items: List<WheelDiagnosticItem>,
    val batteries: List<WheelBatteryDiagnostic>,
)

data class RawBlePacket(
    val timestampMillis: Long,
    val characteristicUuid: String,
    val bytes: ByteArray,
)
