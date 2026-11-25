# Changelog

## 0.1.0 (2026-11-25)

- Merchant API: create a card payment and read it back, with API keys stored as hashes and payments visible only to
  their merchant.
- One charge per `Idempotency-Key`, with keyed request fingerprints instead of stored card numbers.
- acquirer-sim connector with connect and read timeouts that tell "not charged" from "don't know", and a background
  checker that looks up charges whose answer was lost.
- acquirer-sim: a simulated acquirer with test cards, idempotent on the charge reference.
- PostgreSQL schema with Flyway; unit tests, WireMock tests of the provider's failure modes, integration tests in
  Testcontainers, and an 80 % line coverage gate.
- Dockerfiles, compose for a local demo, and a GitLab pipeline for a self-hosted runner.
