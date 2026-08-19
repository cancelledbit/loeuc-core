@file:OptIn(ExperimentalWriteApi::class)

package example

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pw.vasilevskiy.loeuc.shared.api.ChargerProtocol
import pw.vasilevskiy.loeuc.shared.api.ChargerSession
import pw.vasilevskiy.loeuc.shared.api.DeviceFrame
import pw.vasilevskiy.loeuc.shared.api.DeviceMetric
import pw.vasilevskiy.loeuc.shared.api.DeviceTelemetry
import pw.vasilevskiy.loeuc.shared.api.ExperimentalCommands
import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import pw.vasilevskiy.loeuc.shared.api.WheelProtocol
import pw.vasilevskiy.loeuc.shared.api.WheelSession
import pw.vasilevskiy.loeuc.shared.api.toFrames

/**
 * One notification from a Leaperkim Lynx S, and one from an HW smart charger, exactly as they
 * came off the wire. Both are also fixtures in the library's own tests, so if a decode changes,
 * this program's output changes with it.
 */
private const val LYNX_S_NOTIFICATION =
    "DC5A5C4937190000DF6C00007042000300070C8E0379000003C002EE232C00780006007E80C8000080808080808000" +
        "00000BFFFFFFFFFF3211FF430AEF0CDE022A003300000002000501A0CA44"

private const val HW_CHARGER_NOTIFICATION =
    "300600605f430000000000c2474200808a410000c04111041143" +
        "0000000000000000000000000000000000000000000109"

fun main() {
    decodeAWheelNotification()
    decodeAChargerNotification()
    inspectAWriteCommandWithoutSendingIt()
    runBlocking { turnATransportFlowIntoFrames() }
}

/**
 * A session is a decoder, not a connection: it never opens BLE and never writes. Feed it the
 * bytes your own transport delivered, with the timestamp you observed them at.
 */
private fun decodeAWheelNotification() {
    val session = WheelSession.of(WheelProtocol.LeaperKim)

    val updates = session.ingest(LYNX_S_NOTIFICATION.hexToBytes(), timestampMs = 1_000L)

    println("== Wheel")
    println("updates resolved by this chunk: ${updates.size}")
    session.telemetry.report(
        DeviceMetric.SpeedKmh,
        DeviceMetric.PackVoltageV,
        DeviceMetric.OutputCurrentA,
        DeviceMetric.PowerW,
        DeviceMetric.BatteryPercent,
        DeviceMetric.ControllerTemperatureC,
        DeviceMetric.MotorTemperatureC,
        DeviceMetric.TripDistanceKm,
    )
    println()
}

/** Chargers decode the same way; only the protocol enum differs. */
private fun decodeAChargerNotification() {
    val session = ChargerSession.of(ChargerProtocol.HwSmart)

    val update = session.ingest(HW_CHARGER_NOTIFICATION.hexToBytes(), timestampMs = 1_000L)

    println("== Charger")
    println("frame recognised: ${update != null}")
    session.telemetry.report(
        DeviceMetric.ChargeVoltageV,
        DeviceMetric.ChargeCurrentA,
        DeviceMetric.ChargerTemperatureC,
    )
    println()
}

/**
 * Write commands are bytes, never an action: the library hands them over and the caller decides
 * whether to send them. Note the opt-in marker at the top of this file - that is the point where
 * you accept that these bytes are reverse-engineered and can change how a wheel behaves.
 */
private fun inspectAWriteCommandWithoutSendingIt() {
    val command = ExperimentalCommands.leaperKimLight(enabled = true)

    println("== Command")
    println("purpose:  ${command.purpose}")
    println("status:   ${command.validation.status}")
    println("evidence: ${command.validation.evidence}")
    println("bytes:    ${command.bytes.toHex()}")
    println()
}

/**
 * With `loeuc-coroutines`, a transport that already produces byte chunks as a `Flow` can be
 * turned into a flow of typed frames. The decode step stays yours: this is a shape adapter, not
 * a reassembler.
 */
private suspend fun turnATransportFlowIntoFrames() {
    val transport = flowOf(LYNX_S_NOTIFICATION.hexToBytes())

    val frames = transport
        .toFrames { chunk ->
            listOf(DeviceFrame(timestampMs = 1_000L, type = "notify", characteristicUuid = "", bytes = chunk))
        }
        .toList()

    println("== Flow")
    println("frames: ${frames.size}, first frame ${frames.first().bytes.size} bytes")
}

/** Prints the requested metrics, showing "-" for anything this device did not report. */
private fun DeviceTelemetry.report(vararg metrics: DeviceMetric) {
    for (metric in metrics) {
        val value = this[metric]
        println("  ${metric.name.padEnd(24)} ${value?.toString() ?: "-"}")
    }
}

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(16) shl 4) or this[index * 2 + 1].digitToInt(16)).toByte()
    }

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and 0xFF
    "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0F]
}
