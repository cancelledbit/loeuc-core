# Writing a LoEUC extension

An extension is an ordinary Android app that LoEUC binds to while a wheel is connected. It can
read telemetry, add rows to the dashboard's Extensions tile, raise alerts and send commands to
the wheel - each only after the rider has reviewed and allowed exactly what the extension
declares. LoEUC itself is not open source; this contract is.

The typical reason to write one is hardware of your own: a head-up display, a light bar, a
haptic band, a logger. LoEUC already speaks every wheel's protocol; your extension receives the
decoded readings and speaks whatever your device wants.

An extension affects a device somebody is standing on. Declare only what you need, describe it
truthfully, and test with a replayed recording before a real wheel.

## How it fits together

```
 wheel ──BLE── LoEUC ──Binder (AIDL)── your extension ──BLE / USB / Wi-Fi── your hardware
               │  decodes the protocol      │  receives TelemetrySnapshot, 1-5 Hz
               │  owns the wheel link       │  publishes tile rows, raises alerts
               │  executes commands         │  asks for declared commands by key
```

- LoEUC keeps the wheel's Bluetooth link. Your extension never talks to the wheel directly and
  never sees its raw bytes; it gets the same decoded readings the dashboard shows.
- What happens between your extension and your hardware is entirely yours: transport, wire
  format, permissions. LoEUC is not involved.
- Anything that goes back to the wheel goes through LoEUC, by a key you declared, and lands in
  the rider's journal.

A session follows the wheel connection:

```
rider allows the extension         (once, on the consent screen)
wheel connects     -> LoEUC binds your service -> onSessionStart(host, device)
                                                  onSettingChanged(...) for every setting
every snapshot     -> onTelemetry(snapshot)       up to telemetryHz times a second
you call           -> host.publishTile / raiseAlert / sendCommand
command finished   -> onCommandResult(result)
wheel disconnects  -> onSessionEnd(reason)     -> LoEUC unbinds
```

Before the rider allows it, LoEUC never binds the extension and sends it nothing.

## Quick start

1. Copy `examples/android-extension` (a tile and an alert, about 60 lines) or
   `examples/android-extension-hud` (a complete bridge to your own BLE hardware, with an ESP32
   sketch) and rename the package and the `id`.
2. Build and install the APK on a phone that has LoEUC.
3. In LoEUC: Settings -> Link and sensors -> Extensions -> your extension -> Allow.
4. Add the Extensions tile to the dashboard with the plus button.
5. Load a recorded ride in LoEUC (dump playback) - your extension receives the recording's
   telemetry, marked as playback, and no command can reach a wheel. Then try it on a real one.

## Add the dependency

From JitPack:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.cancelledbit.loeuc-core:loeuc-extension-api:<tag>")
}
```

`minSdk` 28 or higher.

## Declare the service

```xml
<service
    android:name=".MyExtensionService"
    android:exported="true"
    android:permission="pw.vasilevskiy.loeuc.permission.BIND_EXTENSION">
    <intent-filter>
        <action android:name="pw.vasilevskiy.loeuc.extension.BIND" />
    </intent-filter>
    <meta-data
        android:name="pw.vasilevskiy.loeuc.extension.MANIFEST"
        android:resource="@raw/loeuc_extension" />
</service>
```

The permission is what stops any app other than LoEUC from binding your service. LoEUC
declares it; never declare it yourself. A service without it, or not exported, is listed as
broken and never bound.

## Write the manifest

`res/raw/loeuc_extension.json` is what the rider sees on the consent screen and the complete
list of what the host will do for you. Anything not declared here is refused.

```json
{
  "id": "com.example.range",
  "apiVersion": 1,
  "version": "1.0",
  "name": { "en": "Range", "de": "Reichweite" },
  "summary": { "en": "Estimates the distance left." },
  "needsLocation": false,
  "telemetryHz": 2,
  "settings": [{ "key": "reserve", "label": { "en": "Reserve" }, "unit": "%",
                 "min": 0, "max": 50, "step": 5, "default": 10 }],
  "tiles": [{ "key": "range", "label": { "en": "Range" }, "unit": "km",
              "decimals": 1, "min": 0, "max": 500 }],
  "alerts": [{ "key": "low", "text": { "en": "Range low" }, "persistence": "repeat" }],
  "commands": [
    { "key": "beep", "label": { "en": "Horn" }, "action": "horn" },
    { "key": "level", "label": { "en": "Light level" },
      "template": { "characteristic": "0000ffe1-0000-1000-8000-00805f9b34fb",
                    "bytes": "aa5503000000",
                    "parameter": { "offset": 4, "width": 2, "endian": "little",
                                   "signed": false, "scale": 1, "min": 0, "max": 100 } } }
  ]
}
```

Texts are objects of language code to text. LoEUC shows the rider's language, then English,
then whatever you provided - give at least `en`.

### Top level

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Stable identifier: lowercase letters, digits, `.` `_` `-`. Keys the rider's consent, settings and journal. Never change it. |
| `apiVersion` | yes | Contract version you wrote against; `1` today. A LoEUC that knows only an older one refuses to bind. |
| `version` | yes | Your version, shown to the rider. |
| `name`, `summary` | yes | Text. |
| `needsLocation` | no, `false` | Ask for the phone's position in snapshots. Granted by a separate switch. |
| `telemetryHz` | no, `5` | Snapshots a second, 1..5. Ask for what you use: a range estimate needs 1, a display 5. |
| `settings`, `tiles`, `alerts`, `commands` | no | Arrays, below. Keys are unique within each. |

### `settings[]` - values the rider sets in LoEUC

| Field | Meaning |
| --- | --- |
| `key` | Passed to `onSettingChanged`. |
| `label`, `unit` | What the rider reads next to the slider. |
| `min`, `max`, `step`, `default` | The slider. `default` until the rider moves it. |

LoEUC stores the values and sends every one of them right after `onSessionStart`, then again on
each change. Your extension does not need its own settings screen.

### `tiles[]` - rows of the Extensions tile

| Field | Meaning |
| --- | --- |
| `key` | Passed to `publishTile`. |
| `label`, `unit` | Row title and unit. |
| `decimals` | 0..4 digits after the point. |
| `min`, `max` | A value outside is not shown: the host treats it as noise, not a reading. |

### `alerts[]`

| Field | Meaning |
| --- | --- |
| `key` | Passed to `raiseAlert`. |
| `text` | Shown on the consent screen. |
| `persistence` | `once` - one beep each time it becomes active; `repeat` - a double beep every 2 s while active; `continuous` - beeps faster as `intensity` rises from 0 to 1. |

Extension alerts go through LoEUC's alert engine and always yield to the rider's own alerts:
an extension cannot talk over a speed or PWM alert.

### `commands[]`

Each has `key` and `label`, and exactly one of `action` or `template` - see [Commands](#commands).

### What asks the rider again

Changing `tiles`, `alerts`, `commands` or `needsLocation` in an update asks the rider to allow
the extension again. Wording, the version string and settings do not. Neither does reformatting
the JSON: the fingerprint is taken from the parsed declaration.

## Implement the service

Subclass `LoEUCExtensionService` and override what you need:

| Callback | When |
| --- | --- |
| `onSessionStart(host, device)` | A wheel connected (or a recording started playing). `device.name`, `device.model` are free text. |
| `onTelemetry(snapshot)` | Every snapshot, at your `telemetryHz`. |
| `onSettingChanged(key, value)` | After `onSessionStart` for every setting, and on each change. |
| `onCommandResult(result)` | A command you sent finished or was refused. |
| `onSessionEnd(reason)` | The session is over; `reason` is a `DetachReason`. |

And call the host through the `host` property (non-null between start and end of a session):

| Call | Does |
| --- | --- |
| `publishTile(key, value)` | Shows `value` in the tile row. `Double.NaN` clears it. A row not updated for 5 s turns into a dash by itself. |
| `raiseAlert(key, active, intensity)` | Starts or stops an alert. |
| `sendCommand(key, value)` | Asks for a declared command. Returns a request id; the answer comes to `onCommandResult`. |
| `capabilities()` | Which of your commands the connected wheel can execute right now. |

```kotlin
class MyExtensionService : LoEUCExtensionService() {
    override fun onTelemetry(snapshot: TelemetrySnapshot) {
        val battery = snapshot.value(ExtensionMetric.BATTERY_PERCENT) ?: return
        host?.publishTile("range", battery * 0.8)
    }
}
```

Rules worth knowing:

- **One thread.** Callbacks arrive on the main thread, in order. Do your own I/O elsewhere if it
  can block; never block the main thread on your hardware.
- **Only LoEUC calls you.** The base class checks the caller's signature on every call and drops
  anything else. Host calls never throw: when LoEUC goes away they return `false`/`null`, and
  `onSessionEnd` follows.
- **Lifetime.** Your process lives while LoEUC holds the binding, and LoEUC runs as a foreground
  service during a ride, so you inherit its priority - no foreground service of your own is
  needed. When the session ends, release your hardware: LoEUC unbinds and the system may stop
  your process.
- **Crashes.** If your process dies, LoEUC rebinds with a growing delay and gives up after five
  quick failures; the rider sees that on the extension's page.
- **Debug builds of LoEUC.** Override `acceptDebugHost` to `true` to accept a LoEUC build not
  signed with a release key. It only takes effect while your own build is debuggable.

## Telemetry

A `TelemetrySnapshot` carries:

- `timestampMillis` - when the readings were taken. During playback, the recording's clock.
- `isPlayback` - `true` for a replayed recording or LoEUC's demo ride.
- `value(metric)` - the reading, or `null` when this wheel does not report it. `null` is normal:
  most wheels lack several of these. Never substitute a zero.
- `latitude`, `longitude` - only with `needsLocation` and the rider's permission, only live, and
  only if the fix is under 5 s old.

| Code | `ExtensionMetric` | Unit |
| --- | --- | --- |
| 1 | `SPEED_KMH` | km/h |
| 2 | `PWM_PERCENT` | % |
| 3 | `PACK_VOLTAGE_V` | V |
| 4 | `CELL_VOLTAGE_AVG_V` | V |
| 5 | `CELL_VOLTAGE_DELTA_V` | V |
| 6 | `BATTERY_CURRENT_A` | A |
| 7 | `OUTPUT_CURRENT_A` | A |
| 8 | `PHASE_CURRENT_A` | A |
| 9 | `POWER_W` | W |
| 10 | `MOTOR_POWER_W` | W |
| 11 | `APPARENT_POWER_VA` | VA |
| 12 | `BATTERY_PERCENT` | % |
| 13 | `CONTROLLER_TEMPERATURE_C` | °C |
| 14 | `MOS_TEMPERATURE_C` | °C |
| 15 | `MOTOR_TEMPERATURE_C` | °C |
| 16 | `BOARD_TEMPERATURE_C` | °C |
| 17 | `BATTERY_TEMPERATURE_MIN_C` | °C |
| 18 | `BATTERY_TEMPERATURE_MAX_C` | °C |
| 19 | `PITCH_DEG` | ° |
| 20 | `ROLL_DEG` | ° |
| 21 | `FALL_PROTECTION_ANGLE_DEG` | ° |
| 22 | `DANGER_SPEED_KMH` | km/h |
| 23 | `TIRE_PRESSURE_BAR` | bar |
| 24 | `TRIP_DISTANCE_KM` | km |
| 25 | `TOTAL_DISTANCE_KM` | km |
| 26 | `CHARGE_VOLTAGE_V` | V |
| 27 | `CHARGE_CURRENT_A` | A |
| 28 | `CHARGE_POWER_W` | W |
| 29 | `CHARGER_TEMPERATURE_C` | °C |
| 30 | `CHARGE_TARGET_VOLTAGE_V` | V |
| 31 | `CHARGED_AMP_HOURS` | Ah |
| 32 | `CHARGED_WATT_HOURS` | Wh |
| 33 | `CHARGING` | 1 or 0 |

Codes are assigned once and never reused. A code your build does not know comes from a newer
LoEUC; skip it.

## Bridging to your own hardware

`examples/android-extension-hud` is a complete, working bridge: telemetry goes to a BLE
head-up display, the display's button comes back as horn and headlight, the display's battery
appears in the Extensions tile. `firmware/loeuc_hud/loeuc_hud.ino` is the ESP32 side. Read them
together; the points below are what generalises to any device.

**Your link is yours.** The extension is a normal app: it declares the Bluetooth (or USB, or
network) permissions it needs and asks for them from an activity of its own - a bound service
cannot request runtime permissions. The example's `SetupActivity` is one button. LoEUC grants
the extension nothing for its own device.

**Tie the link to the session.** Connect in `onSessionStart`, release in `onSessionEnd` and
`onDestroy`. Then nothing scans or holds a connection while no wheel is connected, and the
rider's battery does not pay for a HUD sitting in a drawer.

**Own the wire format, and keep it small.** The example packs seven readings into one 20-byte
write, which fits the default BLE payload without MTU negotiation. Mark absent readings with a
value the device recognises (the example uses `0x7FFF` and friends) rather than sending zero:
"the wheel does not report motor temperature" and "the motor is at 0 °C" are different things
on a display. Put a version byte in, so firmware and app can be updated separately. Pin the
format with a unit test (`HudPacketTest`).

**Drop, do not queue.** At 5 Hz a newer snapshot is 200 ms away. If the previous write is still
in flight, skip this one; a queue only makes the display lag further behind the wheel.

**Inputs become declared commands.** The HUD reports "short press" and "long press"; the
extension maps them to the keys `horn` and `light`, which the manifest declares as the named
actions `horn` and `light.toggle`. LoEUC builds the bytes for whichever wheel is connected and
refuses when it must (no wheel, a replay, power-off at speed). The extension never writes to
the wheel itself.

**Report the device's state through LoEUC.** The HUD's battery is a tile row; losing the HUD
after it was connected raises an alert. The rider then sees and hears everything in one place,
under LoEUC's own rules for alerts.

**Show replays as replays.** `isPlayback` travels to the device (flag bit 0 in the example), so
the display can mark a recording and never pass it off as the live wheel.

## Commands

Two kinds, both declared in the manifest.

**Named actions** - the host builds the bytes for whatever wheel is connected; one declaration
works on every brand:

| `action` | Does |
| --- | --- |
| `light.toggle` | Steps the headlight, the same as LoEUC's light button. |
| `light.auto` | The wheel's own automatic headlight, where it has one. |
| `horn` | The horn. |
| `shutdown` | Powers the wheel off. Refused unless the wheel stands still (under 3 km/h, speed known). |

A named action takes no value. `capabilities()` tells which of yours the connected wheel has.

**Templates** - for hardware LoEUC does not know, such as a controller you added yourself:

```json
"template": {
  "service": "optional service UUID",
  "characteristic": "0000ffe1-0000-1000-8000-00805f9b34fb",
  "bytes": "aa5503000000",
  "withResponse": true,
  "parameter": { "offset": 4, "width": 2, "endian": "little", "signed": false,
                 "scale": 1, "min": 0, "max": 100 }
}
```

You send a key and a value; the host writes `bytes` with `round(value / scale)` placed at
`offset`, `width` (1, 2 or 4) bytes wide. You never hand the host a byte array, so only what the
rider saw can be written. Without `parameter` the command is fixed and takes no value. Behind a
LoEUC bridge node only the wheel's own write characteristic is available.

Every command gets a `CommandResult`; `sent` is the only success, and `outcome` names the reason
otherwise:

| `RefusalReason` | Why |
| --- | --- |
| `SENT` | Handed to the wheel (a named action: queued like LoEUC's own button). |
| `NOT_DECLARED` | The key is not in your manifest. |
| `OUT_OF_BOUNDS` | Value missing, not finite, outside `min..max`, or given to a command that takes none. |
| `NOT_CONNECTED` | No wheel. |
| `PLAYBACK` | A recording or the demo ride is playing. |
| `UNSUPPORTED` | This wheel has no such action, or no such characteristic. |
| `DEVICE_BUSY` | A plugin upload, a bank check or a PID apply holds the link. |
| `RATE_LIMITED` | More than four commands a second from your extension. |
| `FORBIDDEN_PATTERN` | Bytes LoEUC never sends to this brand, such as gyroscope calibration. |
| `MOVING` | Power-off while the wheel moves. |
| `NOT_PERMITTED` | The rider has not allowed this, or the call did not come from your extension. |
| `WRITE_FAILED` | Written, and the link reported a failure. |

Every command is in the rider's journal on the extension's page in LoEUC, with the exact bytes.

## Developing and testing

- **Recordings first.** LoEUC can replay a recorded ride. During playback your extension gets
  telemetry marked `isPlayback`, tiles and alerts behave as on a ride, and every command is
  refused with `PLAYBACK`. This is how to develop without a wheel and without risk.
- **Unit-test your own code.** The parcels are plain classes; build a `TelemetrySnapshot` in a
  test and feed it to your logic. Pin any wire format to your hardware byte for byte.
- **Logs.** `adb logcat -s LoEUCExtension` shows calls the base class dropped; your own tags show
  the rest.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| The extension is not in LoEUC's list | The service lacks the `pw.vasilevskiy.loeuc.extension.BIND` intent filter, or the APK is not installed for the same user. |
| Listed as "cannot run" | Open it: the page lists the problem - missing `android:permission`, not exported, a manifest error with its field, an `id` another app already uses. |
| "Needs a newer LoEUC" | `apiVersion` is higher than this LoEUC knows. |
| Allowed, but never "Running" | It starts only while a wheel is connected or a recording plays. |
| Asks for permission again after an update | `tiles`, `alerts`, `commands` or `needsLocation` changed, or the APK is signed with another key. |
| Tile row shows a dash | Not published for 5 s, or the value is outside the declared `min..max`. |
| Calls are dropped (logcat `LoEUCExtension`) | LoEUC is a debug build: override `acceptDebugHost` in your debug build. |
| A command is refused | `onCommandResult` and the journal name the reason; see the table above. |

## Signing

Any key works; there is no account to register with LoEUC. The rider's consent remembers the
certificate you signed with, so:

- keep your keystore - an update signed with another key is a different extension to the
  rider, and Android will not install it over the old one;
- do not ship builds signed with the Android Studio debug key: it is different on each of your
  machines, and LoEUC marks such extensions on the consent screen;
- key rotation through APK Signature Scheme v3 (`apksigner rotate`) keeps the consent.

## Compatibility

The contract only grows. Fields are appended to parcels, methods to the AIDL interfaces,
metric codes and named actions to their lists; nothing published is changed or removed. An
extension built against an older version keeps working with a newer LoEUC.
