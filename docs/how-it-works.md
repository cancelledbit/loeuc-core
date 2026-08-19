# How it works

The whole library is one idea: **you own the Bluetooth connection, it owns the bytes**. Nothing
here scans, connects, subscribes, writes or waits. You hand it the bytes your platform delivered
and it hands back decoded values.

## Where to start

`WheelSession` for a wheel, `ChargerSession` for a charger. Both are created through a factory
rather than a constructor - the constructor is private so a session cannot exist without knowing
which protocol it speaks:

```kotlin
val session = WheelSession.of(WheelProtocol.LeaperKim)
```

From Swift the same factory is reached through the generated companion:

```swift
let session = WheelSession.companion.of(
    protocol: WheelProtocol.leaperKim,
    options: SessionOptions(emitFrames: false, deviceName: nil, frameWindowCapacity: 0)
)
```

and from Java through `WheelSession.Companion.of(...)`.

Underneath the sessions are the protocol engines - `BegodeProtocolEngine`,
`LeaperKimProtocolEngine`, and the rest. They are public and constructed normally
(`BegodeProtocolEngine()`), and they expose brand-specific detail a neutral session cannot: BMS
pages, diagnostics, settings blocks, firmware versions. Start with a session; drop to an engine
when you need something brand-specific.

## Feed it whatever the transport gave you

`ingest` takes a raw chunk, not a finished frame:

```kotlin
val updates = session.ingest(notification, timestampMs = now, characteristicUuid = uuid)
```

Reassembly happens inside. Every engine keeps a buffer, finds the protocol's marker, waits until
the declared length has arrived, cuts the frame out and keeps the remainder for next time. A
20-byte notification that carries a third of a frame is normal input, and so is a frame that
arrives one byte at a time behind a burst of garbage - the engine resynchronises on the next
marker. When no marker is in sight the buffer is trimmed to its last byte or two, so noise
cannot grow it.

`characteristicUuid` is a label, not a routing key: it is recorded on retained frames and
nothing else. If a device sends two independent framed streams on two characteristics, give each
one its own session.

## What comes back, and what stays behind

`ingest` returns the updates **that chunk resolved**. Three notifications out of four typically
return an empty list, and one chunk can return several updates if it completed more than one
frame. Ignoring the return value is fine - the session keeps the state - but if you want every
value the wheel ever sent (a chart, a maximum, a ride recording), the returned list is the only
place they exist.

`session.telemetry` is a **level, not a stream**. Each update merges into it: metrics in the new
frame overwrite, metrics absent from it are left alone, because a frame that omits a field says
nothing about that field. Last write wins, nothing is queued or averaged. Sixty notifications
carrying 1, 2, 3 … 60 km/h leave `telemetry[SpeedKmh]` at 60.0 whether you read once at the end
or after every one of them.

Two consequences worth designing around:

- **Read as often or as rarely as you like.** Rendering at 60 fps from a wheel that notifies once
  a second is not a problem; the value simply does not change in between.
- **Nothing expires.** If the wheel stops reporting a metric, its last value stays in
  `telemetry` forever. Staleness is not tracked - `session.latest?.timestampMs` tells you when
  the newest frame was ingested, and what counts as too old is your decision.

An absent metric reads as `null`, and that is a different statement from zero: this firmware does
not measure it. Never substitute one for the other; on a wheel that reports no motor temperature,
a displayed 0 °C is a lie a rider might believe.

## Memory

Nothing accumulates with time. `telemetry` is a map keyed by `DeviceMetric`, so it is bounded by
the number of metrics, not by the number of frames. The session holds exactly one
`latest` update, replaced on each one. Engine state is keyed too - cells by index, pages by id,
battery packs by number - never appended.

A million ingests of the same frame, return values discarded, leave the metric count at 15 and
the heap flat.

Raw frames are retained only if you ask:

```kotlin
WheelSession.of(protocol, SessionOptions(emitFrames = true, frameWindowCapacity = 50))
```

That is a ring buffer: the oldest frame is dropped, never the newest. Note that every
`TelemetryUpdate` carries a **copy** of the window, byte arrays included, so a large capacity
costs allocations on every notification rather than just memory. Ask for what you will actually
show. The default, `frameWindowCapacity = 0`, retains nothing.

## Lifecycle and threading

One session per connected device, kept for as long as the connection lives. `reset()` clears
decoded state and the reassembly buffer - call it when reconnecting, and certainly before
pointing a session at a different wheel, or half a frame from the old connection will sit in
front of the first frame of the new one.

Sessions are **not** thread-safe. There is no lock anywhere in the library: it is plain mutable
state behind synchronous calls. Feeding one session from two threads at once will corrupt its
buffer. In practice this costs nothing - BLE callbacks arrive on one thread already - but if
your transport fans out, serialise the calls yourself. `ingest` is synchronous, never blocks and
never waits on the device, so calling it straight from a notification callback is fine.

## Timestamps are yours

Nothing in the library reads a clock. You pass `timestampMs` and it flows into
`TelemetryUpdate.timestampMs` and into `DeviceTelemetry.toSnapshot()`, which is what the alert
model consumes. That is deliberate: a decoder driven by its own wall clock cannot replay a
recorded ride faster than real time, and a replay that behaves differently from a live ride is a
replay you cannot debug with.

Use one clock consistently - a monotonic one, since it measures durations rather than dates.

## Alerts

The second half of the library is a rules engine over the decoded values. It answers one
question per frame: which single alert, if any, should the rider be hearing right now.

```kotlin
val engine = AlertEngine(
    voicePublisher = { voice -> audio.play(voice) },
    alertSource = { myStore.enabledAlerts() },
)

val events = engine.processTelemetry(update.snapshot)
```

`TelemetrySnapshot` is what `DeviceTelemetry.toSnapshot(timestampMs)` produces, and every
`TelemetryUpdate` already carries one, so decoding and evaluating are the same pipeline.

**The engine owns no storage.** Rules live wherever your application keeps them; loading,
migrating and editing them is application work. The engine wants the active list and nothing
else, through an [AlertSource], an explicit `activeAlerts` argument, or a one-off `setAlerts`.
The same goes for sound: it decides what should be heard, never how to make a noise.

**A condition is not a comparison.** It carries a reset threshold distinct from its trigger
threshold, so a value hovering on the line does not chatter, and a minimum hold time, so a
single spike does not raise an alarm. Conditions inside a group are combined with AND, groups
with OR.

**One voice at a time.** Several alerts can hold at once; exactly one sounds. The arbiter picks
by priority, breaks ties by how far each is along its own curve, and holds the winner for a
minimum time so two equally critical alerts cannot swap the slot frame by frame. A strictly more
critical alert cuts in immediately.

**Ramping is the interesting part.** An `Accelerating` alert shrinks its repeat interval and
raises its pitch as the metric climbs between the two thresholds, and past
`continuousFromRatio` it stops beeping and holds one tone. That last step is a separate flag,
not a very small interval: while a gap is above zero there is still a seam, and a seam is
audible. `VoiceTimeline.sampleAt` turns the published voice into gate/frequency/waveform samples
for your audio layer; oscillator phase stays with the renderer so the tone is continuous across
frames.

**Alerts can write settings.** An alert may carry commands that apply when its condition becomes
true and revert when it falls back. These bypass the voice arbiter entirely - a setting write
changes how the wheel behaves, not just what the rider hears, so it must happen even while a
more critical alert holds the sound - and they are handed to your `commandExecutor` as bytes to
send or ignore. Everything in the write section below applies.

**Time comes from the snapshot**, as everywhere else in the library. Durations, cooldowns and
ramps are measured against `snapshot.timestampMs`, which is why a recording replayed at ten
times speed raises exactly the alerts the ride did.

One deliberate omission: a broken alert cannot take down the frame. Each is evaluated inside a
failure boundary, because on Kotlin/Native an uncaught exception ends the process, and a rule
with impossible numbers saved in it must not be able to blank a screen someone is riding behind.

## Writing to a device

The library builds write bytes and never sends them. Every builder that changes a device's state
is marked `@ExperimentalWriteApi` and will not compile without an explicit opt-in, and every
such command is `UNTESTED`: derived from reverse engineering, not confirmed on hardware. See the
"Writing to a wheel" section of the [README](../README.md).
