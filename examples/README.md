# Examples

## Runnable

`examples/jvm` is a real Gradle module in this build, so it cannot quietly stop compiling:

```bash
./gradlew :examples:jvm:run
```

It decodes one Leaperkim Lynx S notification and one HW smart charger notification - both are
byte-for-byte fixtures from the library's own tests - ramps an alert as speed rises, inspects a
write command without sending it, and runs the `Flow` adapter from `loeuc-coroutines`. Output:

```
== Wheel
updates resolved by this chunk: 1
  SpeedKmh                 0.0
  PackVoltageV             141.05
  OutputCurrentA           0.00882
  PowerW                   1.244061
  BatteryPercent           76.55
  ControllerTemperatureC   32.14
  MotorTemperatureC        -
  TripDistanceKm           57.196

== Charger
frame recognised: true
  ChargeVoltageV           145.01588439941406
  ChargeCurrentA           0.0
  ChargerTemperatureC      17.3125

== Alert
   35.0 km/h  events=0  silent
   42.0 km/h  events=1  beeping every 1086 ms, pitch x1.13
   50.0 km/h  events=1  beeping every  630 ms, pitch x1.67
   58.0 km/h  events=1  continuous tone, pitch x2.20

== Command
purpose:  light_on
status:   UNTESTED
evidence: Built by LeaperKimProtocolEngine and covered byte for byte by this library's tests; not confirmed on hardware.
bytes:    4c6b41700d0180800157ed3bd54c6441700d010080016ff832f9

== Flow
frames: 1, first frame 77 bytes
```

`MotorTemperatureC` printing `-` is the point of the absent-value contract: this firmware does
not measure motor temperature, and the library will not invent a zero for it.

The alert section is the ramp in one screen: the same rule beeps slowly at 42 km/h, twice as
often and higher at 50, and stops beeping altogether at 58 - past 90% of the way to its ceiling
it holds one continuous tone. A rider hears where they are on the curve without reading anything.

## Android

There is no Android example module, because the interesting part is three lines inside a
callback you already own. A session is a plain object; keep one per connected device and feed it
whatever arrives:

```kotlin
private val session = WheelSession.of(WheelProtocol.LeaperKim)

override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
) {
    val updates = session.ingest(value, timestampMs = SystemClock.elapsedRealtime())
    updates.lastOrNull()?.let { update ->
        render(update.telemetry[DeviceMetric.SpeedKmh])
    }
}
```

Ingest is synchronous and never waits on the device, so calling it straight from the BLE
callback thread is fine; keep rendering off it as you would anyway. One session per device, and
do not feed one from two threads at once - see `docs/how-it-works.md`.

## iOS

The library compiles for `iosArm64` and `iosSimulatorArm64`. Build a framework from
`:loeuc-core` and call it from `CBPeripheralDelegate`:

```swift
private let session = WheelSession.companion.of(
    protocol: WheelProtocol.leaperKim,
    options: SessionOptions(emitFrames: false, deviceName: nil, frameWindowCapacity: 0)
)

func peripheral(
    _ peripheral: CBPeripheral,
    didUpdateValueFor characteristic: CBCharacteristic,
    error: Error?
) {
    guard let data = characteristic.value else { return }
    let updates = session.ingest(
        chunk: [UInt8](data).map(KotlinByte.init),
        timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
        characteristicUuid: characteristic.uuid.uuidString
    )
    if let speed = updates.last?.telemetry.get(metric: .speedKmh) {
        render(speed.doubleValue)
    }
}
```

Kotlin `Double?` arrives in Swift as `KotlinDouble?`, which is why the value is unwrapped before
use. Nothing here opens a connection: `CBCentralManager` stays yours.
