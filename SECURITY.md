# Security

Report anything sensitive privately to the maintainer before disclosing it publicly. Do not put
credentials, personal device identifiers, or private captures into an issue or a pull request.

What this library does and does not do matters for your threat model:

- It never opens a connection and never transmits. Every function is a pure transformation of
  bytes the caller supplied.
- It never persists anything. Retained raw frames live in a bounded in-memory window that is
  off by default.
- Bytes that would change a device's state are only produced by builders marked
  `@ExperimentalWriteApi`, and sending them is the caller's decision and the caller's transport.
  Every write command is `UNTESTED`: it has not been confirmed against hardware. Do not send one
  to a wheel without independent validation and a safety procedure.

BLE permissions, transport security, and protecting captured traffic remain the consumer's
responsibility.
