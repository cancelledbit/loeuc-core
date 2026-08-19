# Contributing

A change to wire parsing needs a test that pins the bytes. Paste the frame from a capture as
hex - do not retype it, and do not hand-craft one that only proves the parser agrees with
itself.

New write commands need wire evidence: a capture of the vendor application sending them, a
confirmed observation on real hardware, or a disassembly that another reader can follow to the
same bytes. A single plausible-looking reading is a hypothesis, and hypotheses do not ship as
writes.

Keep public documentation and code comments in English. Russian belongs in string literals - the
display labels this library ships - and nowhere else. Never add device identifiers, private
paths, credentials, or captures containing someone else's data.

Before opening a pull request:

```bash
./gradlew checkOpenSourceHygiene checkOpenSourceHygieneFixture \
    :loeuc-core:jvmTest :loeuc-coroutines:jvmTest :examples:jvm:run
```
