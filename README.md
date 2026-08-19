# loeuc-core

Kotlin Multiplatform decoders for electric-unicycle and charger Bluetooth traffic.

Give it the bytes your BLE stack delivered; get back typed telemetry. The library never opens a
connection, never writes to a characteristic, never touches storage and has no UI. That is a
deliberate boundary, not an unfinished one: transport, permissions and safety decisions belong
to the application, and everything here is a pure function of the bytes it was handed.

```kotlin
val session = WheelSession.of(WheelProtocol.LeaperKim)
val updates = session.ingest(notification, timestampMs = 1_000L)

session.telemetry[DeviceMetric.SpeedKmh]       // 0.0
session.telemetry[DeviceMetric.PackVoltageV]   // 141.05
session.telemetry[DeviceMetric.MotorTemperatureC] // null - this firmware does not measure it
```

A metric that comes back `null` was not reported. It is never a zero standing in for "unknown",
which is the distinction that decides whether an app can show a number at all.

## What it decodes

| Protocol | `WheelProtocol` / `ChargerProtocol` | Notes |
| --- | --- | --- |
| Begode | `Begode` | Legacy and modern frames, per-cell battery curve |
| KingSong | `KingSong` | Telemetry and BMS pages |
| InMotion | `Inmotion` | V11/V12/V13/V14/P6 telemetry, BMS, diagnostics, settings block |
| Leaperkim | `LeaperKim` | Lynx S / Patton pages, BMS, settings, diagnostic co-stream |
| NOSFET | `Nosfet` | Aero, Aeon, Apex and Xeno speak the Leaperkim protocol; same engine, separate identity |
| Ninebot Z | `Ninebot` | Framing with response-envelope validation, settings catalog |
| Solowheel Xtreme | `SolowheelXtreme` | ASCII stream: speed and pack voltage only |
| HW smart charger | `HwSmart` | Telemetry, password-gated chargers detected |
| SKAT / CAN-Control | `SkatCanControl` | Unsolicited telemetry stream |

Coverage differs per brand and per firmware. Where a field is a hypothesis rather than a
confirmed reading, the code says so at the line that decodes it. Comments occasionally cite
`docs/<brand>-protocol-notes.md`; those are the maintainer's research notes and are not part of
this repository.

## Modules

- `loeuc-core` - engines, the neutral telemetry model, sessions, the alert data model. No
  dependencies beyond the Kotlin standard library.
- `loeuc-coroutines` - one adapter that turns a `Flow<ByteArray>` transport into a flow of typed
  frames. Optional, and the only place kotlinx-coroutines appears.

Targets: JVM, Android (host tests), `iosArm64`, `iosSimulatorArm64`.

## Using it

Nothing is published to Maven Central yet, so take it as a source dependency. In your
`settings.gradle.kts`:

```kotlin
includeBuild("path/to/loeuc-core")
```

and depend on `pw.vasilevskiy:loeuc-core:0.1.0`, which Gradle substitutes with the included
build. Or vendor the directory and `include(":loeuc-core")` directly - that is what the
application this was extracted from does, and it is why the library is the same source the app
compiles rather than a copy that drifts out of date.

`examples/` has a runnable JVM program and the Android and iOS integration boundaries.

## Writing to a wheel

Reading is safe. Writing is not, and the API says so out loud: every builder that produces bytes
which change a device's state is marked `@ExperimentalWriteApi` and will not compile until you
opt in.

```kotlin
@OptIn(ExperimentalWriteApi::class)
fun lightsOn() = ExperimentalCommands.leaperKimLight(enabled = true)
```

These byte sequences come from reverse engineering. A wrong one lands on a machine somebody is
standing on. `ExperimentalCommands` wraps the same builders with a `CommandValidation` saying
how the bytes were established and whether anyone confirmed them on hardware - currently, for
every write, nobody has:

```kotlin
val command = ExperimentalCommands.leaperKimLight(enabled = true)
command.validation.status    // UNTESTED
command.validation.evidence  // where the bytes came from
command.bytes                // yours to send, or not
```

The library still sends nothing. Gyroscope calibration is deliberately absent on every brand:
the real procedure needs the wheel physically levelled first, and no application can verify
that.

## Building

From this directory, with the checked-in wrapper:

```bash
./gradlew checkOpenSourceHygiene checkOpenSourceHygieneFixture \
    :loeuc-core:jvmTest :loeuc-coroutines:jvmTest :examples:jvm:run
```

CI additionally runs the Android host tests and compiles for the iOS simulator without signing.

## Compatibility

The API is experimental until `1.0.0`. New metric enum values are additive; callers must treat
an unknown metric and an absent reading as normal protocol states, because both happen on real
wheels every day.

## License

MIT. See [LICENSE](LICENSE).
