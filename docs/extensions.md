# Writing a LoEUC extension

An extension is an ordinary Android app that LoEUC binds to while a wheel is connected. It can
read telemetry, add rows to the dashboard's Extensions tile, raise alerts and send commands to
the wheel - each only after the rider has reviewed and allowed exactly what the extension
declares. LoEUC itself is not open source; this contract is.

An extension affects a device somebody is standing on. Declare only what you need, describe it
truthfully, and test with a replayed recording before a real wheel.

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
declares it; never declare it yourself. A service without it is listed as broken and never bound.

## Write the manifest

`res/raw/loeuc_extension.json` is what the rider sees on the consent screen and the complete
list of what the host will do for you. See `ExtensionManifest.parse` for every field; the
example in `examples/android-extension` uses most of them.

- `id` - stable, lowercase. It keys the rider's consent, settings and journal.
- `telemetryHz` - 1 to 5. The host never delivers more than 5 snapshots a second.
- `needsLocation` - the rider grants it separately. Without it, the position is absent from the
  parcel, not hidden.
- `tiles`, `alerts`, `commands` - every key you will use. An undeclared key is refused.

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

Changing `tiles`, `alerts`, `commands` or `needsLocation` in an update asks the rider again.
Wording, the version string and settings do not.

## Implement the service

Subclass `LoEUCExtensionService`. Callbacks arrive on the main thread, in order, and only from a
LoEUC build signed with a release certificate - the base class checks the caller on every call.

```kotlin
class MyExtensionService : LoEUCExtensionService() {
    override fun onTelemetry(snapshot: TelemetrySnapshot) {
        val battery = snapshot.value(ExtensionMetric.BATTERY_PERCENT) ?: return
        host?.publishTile("range", battery * 0.8)
    }
}
```

While developing against a debug build of LoEUC, override `acceptDebugHost` to `true`. It only
takes effect while your own build is debuggable.

## Commands

Two kinds, both declared in the manifest:

- **Named actions** - `light.toggle`, `light.auto`, `horn`, `shutdown`. The host builds the bytes
  for whatever wheel is connected; one declaration works on every brand.
- **Templates** - a characteristic UUID, bytes, and optionally one parameter field. You send a
  key and a value; the host assembles the bytes from the template the rider saw. You never hand
  the host a byte array.

Every command gets a `CommandResult`. Anything other than `RefusalReason.SENT` names why it was
not sent: playback, no connection, the wheel busy with an update, too fast (more than four a
second), out of bounds, power-off while moving, or a write the host never sends to that brand,
such as gyroscope calibration. Every command is in the rider's journal with the exact bytes.

## Playback

When the rider replays a recording, snapshots arrive with `isPlayback == true` and every command
is refused. That makes a recording the safest way to develop an extension: the tile and the
alerts behave as they would on a ride, and nothing reaches a wheel.

## Signing

Any key works; there is no account to register with LoEUC. The rider's consent remembers the
certificate you signed with, so:

- keep your keystore - an update signed with another key is a different extension to the
  rider, and Android will not install it over the old one;
- do not ship builds signed with the Android Studio debug key: it is different on each of your
  machines, and LoEUC marks such extensions on the consent screen;
- key rotation through APK Signature Scheme v3 (`apksigner rotate`) keeps the consent.
