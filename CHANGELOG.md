# Changelog

## Unreleased

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
