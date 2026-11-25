# Changelog

## 0.3.1 (2026-11-25)

- The pipeline runs on GitLab's own runners, picked by their tag, instead of a runner on the developer's machine:
  GitLab shows a runner's internet address in the job details of a public project.
- Docker-in-Docker pulls Docker Hub images through Google's copy of Docker Hub, since GitLab's runners share their
  addresses and Docker Hub limits anonymous pulls per address.

## 0.3.0 (2026-11-25)

- monobank: a second provider, through monobank's acquiring API. It charges with the card's details
  (`payment-direct`), sends the customer to monobank's 3-D Secure page and back to the gateway, and turns monobank's
  error codes into the gateway's decline codes. A charge whose answer was lost is found in monobank's statement by
  the payment's id.
- monobank's webhooks: `POST /providers/monobank/webhooks` takes a report only with monobank's ECDSA signature
  (`X-Sign`), then looks the payment up with monobank rather than taking the report's word.
- `GATEWAY_PROVIDER` picks the provider for new payments (acquirer-sim unless set). Each payment is looked up with
  the provider that charged it, so switching leaves payments in flight with their own.
- A provider still working on a charge keeps the payment as it is, and its id for the charge is stored, so later
  lookups ask by it.
- Decline codes are documented as the same whichever provider charged the card.
- GitHub's secret scanning skips the test folders, whose example secrets are published test vectors.

## 0.2.0 (2026-11-25)

- 3-D Secure: a payment the card's bank wants confirmed is `REQUIRES_ACTION` with a `nextAction` link. The
  customer's browser comes back to `/3ds/return/{id}`, the gateway settles the payment from the provider's answer
  and sends the browser on to the request's new `returnUrl`. The checker settles challenges left unfinished.
- Webhooks: every status a payment reaches after `PROCESSING` is written to an outbox in the same transaction, then
  sent to the merchant's endpoint signed by the Standard Webhooks scheme, retried for about 18 hours and given up
  after that, or at once on 410 Gone. Redirects are not followed.
- acquirer-sim: test card 4000 0000 0000 3220 asks for a challenge on a page of its own, which passes, fails, or
  expires after 15 minutes.
- merchant-sim, a new app that plays the shop: it checks webhook signatures, deduplicates by `webhook-id`, shows the
  page the customer returns to, and can fail deliveries on purpose to show the retries.
- The pipeline tries again when fetching the repository or the cache fails, instead of failing the job.
- Request fingerprints include the return URL; a request without one keeps the fingerprint it had, so replays of
  older payments still match.

## 0.1.2 (2026-11-25)

- README: pipeline and coverage badges from GitLab, the curl examples end their output with a new line, and an
  example of reading a payment back.
- `docs/gitlab-setup.md` follows the setup as it was actually done: a protected runner locked to the project,
  replacing a runner token that got out with `reset-token`, checking GitLab's SSH host key, and a GitHub mirror token
  limited to this repository.

## 0.1.1 (2026-11-25)

- The demo's host ports can be moved in `.env` (`GATEWAY_PORT`, `ACQUIRER_SIM_PORT`, `GATEWAY_DB_PORT`), for
  machines where 8090, 8091 or 5436 is taken.
- Both OpenAPI files use a relative server URL, so Swagger UI works on whatever port it is served from.
- Jars are named without the version (`gateway-service.jar`), so the Dockerfiles copy one known file even when an
  older jar is still in `target/`.
- Connection errors in the log name their type: a refused connection logged `null` before.

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
