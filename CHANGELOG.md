# Changelog of `property-payment-api`

Every release of this repository, newest first. One release per course cut, tagged on `main`
(`git-conventions.md`, "Tags and versioning"; SemVer). Each entry lists the user stories it
brought, with the pull requests of `develop` that built them.

## [3.0.0] - 2026-11-15

### Added

- **HU-PAY-001 (code-corhuila/property-docs#162) — Charge a Reserva**
  - chore(payment): scaffold the three-module spring boot service (#1)
  - feat(payment): add the pago and reembolso domain model and its invariants (#2)
  - feat(payment): add the common http adapters (#3)
  - docs(payment): publish the versioned openapi contract (#4)
  - feat(payment): add the ledger port and its in-memory adapter (#5)
  - chore(payment): run the integration tests against the payment schema (#6)
  - feat(payment): keep the ledger in the payment schema (#7)
  - feat(payment): charge and refund through stripe behind a circuit breaker (#8)
  - feat(payment): charge a reservation for the saga (#9)
  - feat(payment): relay the outbox to the broker in order (#12)
  - feat(payment): run the outbox relay as a process apart (#13)
  - feat(payment): pass STRIPE_URL and report the gateway in the health (#14)
- **HU-PAY-004 (code-corhuila/property-docs#193) — Refund an approved charge**
  - feat(payment): refund an approved payment (#10)
- **HU-PAY-003 (code-corhuila/property-docs#192) — Maintain and read the transaction ledger**
  - feat(payment): read a payment and its refund (#11)
