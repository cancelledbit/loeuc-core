package pw.vasilevskiy.loeuc.shared.protocol

class InmotionDiagnosticsEngine {
    fun decodeMain(payload: ByteArray): List<WheelDiagnosticItem> {
        if (payload.isEmpty()) return emptyList()
        return InmotionV14DiagnosticDefinitions.mapIndexedNotNull { index, definition ->
            val payloadOffset = index / 8
            val bitOffset = index % 8
            val byte = payload.getOrNull(payloadOffset)?.toInt()?.and(0xFF) ?: return@mapIndexedNotNull null
            WheelDiagnosticItem(
                index = index,
                payloadOffset = payloadOffset,
                bitOffset = bitOffset,
                rawValue = (byte ushr bitOffset) and 0x01,
                categoryEn = definition.categoryEn,
                categoryRu = definition.categoryRu,
                titleEn = definition.titleEn,
                titleRu = definition.titleRu,
                severity = definition.severity,
            )
        }
    }

    fun decodeBatteries(payload: ByteArray): List<WheelBatteryDiagnostic> {
        val slotSize = 16
        return (0 until minOf(payload.size / slotSize, 4)).map { slotIndex ->
            val offset = slotIndex * slotSize
            val state = payload.u8(offset + 6)
            val faults = payload.u8(offset + 7)
            WheelBatteryDiagnostic(
                index = slotIndex + 1,
                detected = state.bit(0),
                enabled = state.bit(1),
                charging = state.bit(2),
                voltage = (payload.u8(offset) or (payload.u8(offset + 1) shl 8)) / 100.0,
                chargeCurrent = payload.i16le(offset + 2) / 100.0,
                dischargeCurrent = payload.i16le(offset + 4) / 100.0,
                hasFault = state.bit(6) || state.bit(7) || (0..5).any { faults.bit(it) },
                hexData = payload.copyOfRange(offset, offset + slotSize).toHex()
            )
        }
    }

    private fun Int.bit(index: Int): Boolean = ((this ushr index) and 1) != 0
    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
    private fun ByteArray.i16le(offset: Int): Int {
        val v = u8(offset) or (u8(offset + 1) shl 8)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

private data class DiagnosticDef(
    val categoryEn: String,
    val categoryRu: String,
    val titleEn: String,
    val titleRu: String,
    val severity: WheelDiagnosticSeverity,
)

private fun err(cEn: String, cRu: String, tEn: String, tRu: String) =
    DiagnosticDef(cEn, cRu, tEn, tRu, WheelDiagnosticSeverity.Error)
private fun wrn(cEn: String, cRu: String, tEn: String, tRu: String) =
    DiagnosticDef(cEn, cRu, tEn, tRu, WheelDiagnosticSeverity.Warning)

private val InmotionV14DiagnosticDefinitions = listOf(
    err("Driver board", "Силовая плата", "Phase current sensor fault", "Ошибка датчика фазного тока"),
    err("Driver board", "Силовая плата", "Bus current sensor fault", "Ошибка датчика тока батареи"),
    err("Motor", "Мотор", "Left Hall sensor fault", "Ошибка левого датчика Холла"),
    err("Motor", "Мотор", "Right Hall sensor fault", "Ошибка правого датчика Холла"),
    err("Battery", "Батарея", "Battery fault", "Ошибка батареи"),
    err("Driver board", "Силовая плата", "IMU sensor fault", "Ошибка гироскопа (IMU)"),
    err("Communication", "Связь", "Driver board communication fault 1", "Ошибка связи с силовой платой 1"),
    err("Communication", "Связь", "Driver board communication fault 2", "Ошибка связи с силовой платой 2"),
    err("Communication", "Связь", "HMIC communication fault 1", "Ошибка связи с платой экрана (HMIC) 1"),
    err("Communication", "Связь", "HMIC communication fault 2", "Ошибка связи с платой экрана (HMIC) 2"),
    err("Driver board", "Силовая плата", "MOS temperature sensor fault", "Ошибка датчика температуры мосфетов"),
    err("Motor", "Мотор", "Motor temperature sensor fault", "Ошибка датчика температуры мотора"),
    err("Driver board", "Силовая плата", "Board hot-area sensor fault", "Ошибка датчика температуры платы"),
    err("Cooling", "Охлаждение", "Fan fault", "Ошибка вентилятора"),
    err("HMIC", "Плата экрана", "HMIC RTC fault", "Сбой часов на плате экрана"),
    err("HMIC", "Плата экрана", "HMIC flash fault", "Сбой памяти на плате экрана"),
    err("Driver board", "Силовая плата", "Bus voltage sensor fault", "Ошибка датчика напряжения батареи на плате"),
    err("Battery", "Батарея", "Battery voltage sensor fault", "Ошибка датчика напряжения батареи"),
    err("Battery", "Батарея", "Battery cannot power off", "Батарея не отключается"),
    err("Battery", "Батарея", "Battery cannot charge", "Батарея не может заряжаться"),
    wrn("Battery", "Батарея", "Critically low battery", "Критически низкий заряд"),
    wrn("Battery", "Батарея", "Battery overvoltage", "Перенапряжение батареи"),
    wrn("Driver board", "Силовая плата", "Overcurrent", "Превышение тока"),
    wrn("Battery", "Батарея", "Low battery", "Низкий заряд батареи"),
    err("Battery", "Батарея", "Additional battery fault", "Ошибка батареи (вторая)"),
    wrn("Motor", "Мотор", "Motor overtemperature", "Перегрев мотора"),
    wrn("Temperature", "Температура", "Vehicle overtemperature", "Общий перегрев"),
    wrn("Driver board", "Силовая плата", "CPU overtemperature", "Перегрев процессора"),
    wrn("Driver board", "Силовая плата", "IMU overtemperature", "Перегрев гироскопа (IMU)"),
    wrn("Safety", "Безопасность", "Locked because of a safety issue", "Колесо заблокировано защитой"),
    wrn("Safety", "Безопасность", "Overspeed", "Превышение скорости"),
    wrn("Motor", "Мотор", "Unexpected motor spin", "Мотор крутится, когда не должен"),
    wrn("Motor", "Мотор", "Motor blocked", "Мотор заблокирован"),
    wrn("Safety", "Безопасность", "Fall detected", "Обнаружено падение"),
    wrn("Safety", "Безопасность", "Risky riding behavior", "Рискованная езда (предупреждение колеса)"),
    wrn("Motor", "Мотор", "Motor no-load protection", "Сработала защита: мотор крутился без нагрузки"),
    wrn("Safety", "Безопасность", "Required self-check not passed", "Колесо не прошло самопроверку"),
    wrn("Controls", "Управление", "Power key held too long", "Слишком долгое нажатие кнопки питания"),
    wrn("Battery", "Батарея", "Some batteries are not enabled", "Часть батарей не включена"),
    wrn("Battery", "Батарея", "Battery calibration required", "Требуется калибровка батареи"),
    wrn("Compatibility", "Совместимость", "Software incompatible", "Несовместимая прошивка"),
    wrn("Firmware", "Прошивка", "Functions limited by incomplete firmware update", "Обновление прошивки не завершено — часть функций отключена"),
    wrn("Safety", "Безопасность", "Remote lock active", "Колесо заблокировано удалённо"),
    wrn("Compatibility", "Совместимость", "Hardware incompatible", "Несовместимое оборудование"),
    wrn("Cooling", "Охлаждение", "Fan speed too low", "Слишком низкая скорость вентилятора"),
)
