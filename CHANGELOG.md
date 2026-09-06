# Changelog

## Unreleased

### Protocol engines

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
