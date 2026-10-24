# Changelog

## Unreleased

### Protocol engines

- LeaperKim: `buildModuleCommandFrame` accepts module id 13, the configurable field weakening of
  the Lynx-S b78 monolith (start, full point and maximum of the d-axis ladder).
- LeaperKim: `recentFrames()` keeps 24 frames instead of 16, so a plugin page that a host
  occasionally replaces with a second page still stays in the window.
- Begode: most settings now read back from the telemetry frames. `BegodeTelemetry` gains
  `sideTiltThreshold` and `ledMode` (`type=4`), `fieldWeakening` and `sideTiltAngleIndex`
  (`type=7 @5`), and `pedalDip`, `pedalTrim` and `buzzerVolume` (`type=0`, stock firmware only,
  after a `type=7` frame), each `null` until seen or when out of range, and
  `BegodeSettingsCatalog` fills those rows and the first-speed-alarm switch with them. The
  offsets come from two independent readings of WheelHUD's native Begode decoder and are
  consistent with every capture available, none of which changes a setting mid-ride. `type=4`
  settings (ride mode, tilt-back, units, auto power-off) are now read on legacy-format streams too:
  a wheel that sends `type=7` but no `type=1` used to get none of them.
- Begode: the battery current is corrected against the smart BMS. `type=1,page=0..3 @8` carries
  each BMS's own pack current; the engine keeps the controller's fast changes and takes the level
  from the BMS pair, after learning from the ride which way that BMS signs its current (about half
  a minute of riding). An X-Way's controller reads 1.37 times low and a RACE's parks at -2.8 A;
  both now integrate to what the BMS measures. Wheels without BMS current, and sessions that
  never move, publish the controller's reading as before.
- InMotion P6: the integrated realtime answer (`0x87`) is decoded. `integratedRealtimeCommand()`
  asks for it once main info has named a P6. The answer repeats the realtime values in its own
  layout, and the engine lays them over the last `0x04` reading, so an app that asks for both
  gets fresh speed, PWM and the rest twice as often. It also carries what no other answer does:
  the wheel's own trip maxima (speed, battery power, motor power) and its LTE signal, now
  `InmotionTelemetry.wheelMaxSpeedKmh`, `wheelMaxPowerW`, `wheelMaxMotorPowerW` and `lteSignal`,
  NaN until the first answer.
- LeaperKim: the horn is audible on purpose now, and `buildNoOpActionCommand` is renamed
  `buildHornCommand` (experimental write API) because the old name was wrong. `packet[5]` of an
  `LdAp` frame is a two-decimal-digit beep request - tens pick the duration, units the loudness -
  and the wheel sounds it before it even looks at which command the frame carries (`0x08011DF4` ->
  `0x0800DE94` on a NOSFET Aero `502.0.06`, `0x0801263C` -> `0x0800E064` on a Lynx-S `009.0.04`).
  The horn now asks for `0x63`, 450 ms at loudness 900 of 1023, instead of the 50 ms at 100 that
  every command sends as an acknowledgement; light and settings writes keep the quiet code. The
  frame still changes no wheel setting - all nine setters of the legacy layout range-reject the
  `0x80` placeholders - but it was never the no-op the old name claimed: two stores run ahead of
  any range check. The vendor app's own horn (`CMD_OLDCMDB`, an `LkAp` frame) is deliberately not
  used: no `LkAp` frame reaches the dispatcher on an Aero, and the Lynx-S image has no `LkAp`
  string at all.
- Begode: `BegodeIdentityRequests` no longer queries a wheel that has stopped streaming. A stock
  wheel (`GW2026304`) goes silent while bytes arrive and resumes about 1.2 s after the last one;
  asked every 100 ms up to 20 times each, it stayed silent past a 5 s connection watchdog. Queries
  now go out at most twice each, at least 1.5 s apart, and only while a notify arrived in the last
  0.5 s. Breaking: `onNotify(chunk)` is now `onNotify(chunk, nowMillis)`, and `seed(modelName,
  firmware)` records answers read before the session started.
- LeaperKim: firmware flashing as a platform-neutral API. `LeaperKimFirmwareImage` validates a
  container or raw image before anything is written, `LeaperKimFirmwareManifest` matches it
  against known builds, and `LeaperKimFirmwareFlashSession` (experimental write API) drives the
  official app's sequence - `AT+RINTOPRO`, wait for telemetry silence, 20-byte chunks to `ffe1`
  with or without response, one no-response retry on a rejected first write - through a small
  port for clock, writes and timers. Pinned by `firmware/leaperkim/flash-transcripts.golden`.
- Begode: the modern battery current (`type=7 @2`) is published positive on a draw. The wire
  counts a draw as negative, and the engine passed it through, so a modern Begode ridden forwards
  reported negative battery power and a negative consumption integral; `apparentPower` was signed
  as well and is now a magnitude. Legacy streams are unchanged.
- Current keeps its sign. Both Begode and LeaperKim took the magnitude of the current word
  before anyone downstream could see it, so energy returned by braking was integrated as energy
  spent - 47 % over on a 20 000-frame Begode ride. `PowerFlow` decides the direction by
  comparing the current sign against the direction of travel, because the speed word carries a
  direction too; PWM, phase current and apparent power stay magnitudes on purpose.
- Begode: the series-cell count is no longer guessed from one voltage reading (a full 36S and a
  40S at 3.78 V per cell both read 151.2 V, and the 90 V bucket boundary sat in the middle of a
  24S pack's working range). The consumer pushes the count in through `applyPackSpec`, from a
  catalogue or the rider's own pick; without it the charge stays unknown rather than wrong.
- LeaperKim: hardware 0030 (Sherman-S) reads its charge off the 24S curve; the hand-written
  Sherman table that could only express 0..53 % is derived from the validated Apex curve like
  the Oryx one. Motor and coil temperature decode from main-frame @40..43 where a patched
  firmware puts them, guarded so stock's 0x80 sentinels read as unavailable. `angle_trim` rounds
  the wheel's hundredths of a degree to the nearest tenth instead of truncating toward zero.
- LeaperKim: `buildPluginUploadFrames` and `buildPluginCommandFrame` (experimental write API)
  produce the frames for the firmware plugin slots; `usesTextLightCommand` tells the text and
  binary light command families apart by hardware code.
- InMotion: the direct BMS reads are decoded here. `InmotionDirectBms` reads the `0x16`
  many-to-many answers - per-cell voltages, the pack temperature array, capacities and the
  spread the pack reports itself - and `InmotionProtocolEngine` merges them with the `0x05`
  battery frame, so `InmotionBmsSnapshot` now carries cells and temperatures. A pack whose BMS
  answers before the wheel's own battery frame produces a battery of its own, marked
  `reported = false` so a consumer can tell "the wheel says no fault" from "nobody asked".
  Only a P6 asks these reads; every other model answers nothing.
- Begode: `bmsSnapshot()` splits the `type=2`/`type=3` cell pages into the two packs they came
  from, and `BegodeTelemetry` carries the battery's highest and lowest temperature from the
  `type=1` pack summary. A wheel without those sensors answers a zero pair, which stays `NaN`
  rather than becoming a battery at freezing point.
- Every engine exposes `modelName()`; telemetry carries `coilTemperature`.

### Alerts

- Spoken alerts: `AlertType.Spoken`, `SpokenAnnouncement`, `AnnouncementComposer` and
  `MetricSpeech` turn a template and a metric value into a sentence in either language, with
  the right unit word and number form; `AnnouncementArbiter` picks one announcement at a time
  and the engine holds the rest while the consumer's speech synthesiser is busy.
- Step conditions: a condition can fire once per threshold step (`ConditionTemplate.stepIndexFor`,
  `advanceStep`) and re-arm when the value falls back, instead of once per crossing.
- New metrics: battery temperature and GPS speed, with their sources listed in
  `MetricIdSources`.

### Library

- Breaking: a refused settings write now carries both languages. `SettingWriteResult.Rejected`,
  `KingSongSettingWrite.Rejected` and `InmotionLegacySettingWrite.Rejected` take a
  `SettingWriteRefusal(english, russian)` instead of a `reason: String`, and `text(isRu)` picks
  the wording; `reason` remains as the English one for logs and tests. A single English sentence
  was reaching a Russian interface inside a translated frame. A consumer that built a rejection
  itself now fails to compile rather than silently keeping English.

### Library (already on the public main since 2026-08-19)

- The alert engine is part of the library: conditions with hysteresis and hold time, the
  one-voice arbiter, ramping cadence and the voice timeline. It stores nothing - alerts arrive
  through the new `AlertSource`, an explicit argument, or `setAlerts` - so persistence, editing
  and migrations stay with the consumer.

- Relicensed under MIT.
- Write commands are public again, behind the `@ExperimentalWriteApi` opt-in marker, and
  `ExperimentalCommands` now delegates to the protocol engines instead of carrying its own copy
  of the byte layouts and a second CRC32 implementation.
- Russian display labels are part of the API again: wheel setting titles, metric names and
  diagnostic text ship in both languages. An earlier release stripped them, which also emptied
  out the comments that were written in Russian; those comments are translated now.
- `examples/jvm` is a runnable Gradle module rather than a set of snippets that never compiled.
- Removed Maven coordinates from the README: nothing is published yet.

## 0.1.0

- Neutral finite telemetry values and bounded raw-frame storage.
- Ninebot Z response-envelope validation.
- Optional Kotlin Flow frame adapter.
