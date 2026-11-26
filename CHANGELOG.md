# Changelog

## 0.7.0 (2026-11-26)

- A Helm chart, `deploy/helm/payment-gateway`, for one environment per namespace, with values for dev, staging and
  production. dev runs one pod of each app; staging runs the gateway and risk-service as production would, two or
  more of each, the gateways autoscaled, both with disruption budgets; production has no simulators, takes
  PostgreSQL and Redis as managed services and pays through the real monobank. `values.schema.json` refuses an
  unknown or misspelt value.
- In every environment: probes on a management port of its own (8081), which the Ingress doesn't route; a wait
  before each pod stops, so rollouts lose no requests; the restricted Pod Security Standard and a read-only root
  file system; network policies that let each pod reach only what it needs; secrets from a Secret made outside the
  chart. The gateways balance their gRPC calls over every risk-service pod, through a headless Service, and
  risk-service ends connections after a minute, so new pods get calls too.
- `deploy/k3d`: a local cluster of three nodes (`create-cluster.sh`), `deploy.sh` to build the images and put dev
  or staging on it, and `smoke-test.sh`, which pays, refunds and goes through 3-D Secure there from outside, checks
  the network policies from inside the pods and, in staging, that every risk-service pod gets calls and that a
  rollout of the gateway loses no payment.
- The gateway needs acquirer-sim's API key only when `GATEWAY_PROVIDERS` names acquirer-sim, as with monobank's
  token, so an environment without the simulator needs no key for it.
- The gateway's database migration tries to connect for about a minute, since on Kubernetes the database may come
  up after the gateway.
- The pipeline has a `chart` job: `helm lint`, `helm template` for each environment, the output checked against
  Kubernetes 1.35's schemas with kubeconform, and values the chart must refuse (`ci/check-chart.sh`).

## 0.6.1 (2026-11-26)

- acquirer-sim's tests of monobank's `invoice/cancel` give each refund a reference of its own. They all used one,
  and since the tests share one application context, the simulator kept the first test's refund under it and
  refused the others with 400: three tests failed, and the build stopped there.
- The README says to start compose once the build has passed: compose builds its images from the jars, and a
  failed build leaves some of them as the build before made them. `/actuator/info` shows each app's version.

## 0.6.0 (2026-11-26)

- Refunds: `POST /api/v1/payments/{id}/refunds` refunds a `SUCCEEDED` payment in full or in parts, once per
  Idempotency-Key, through the provider that charged it; `GET` lists a payment's refunds or reads one. A refund is
  stored under a lock on its payment before the provider is asked, so refunds sent side by side never give back
  more than was charged, and one whose answer got lost is looked up in the background, as a payment is.
- New codes: 409 `PAYMENT_NOT_REFUNDABLE` and `REFUND_AMOUNT_TOO_LARGE`, 404 `REFUND_NOT_FOUND`. A payment shows its
  `refundedAmount`, and the merchant gets a `refund.succeeded` or `refund.failed` webhook for each refund.
- The connectors refund through acquirer-sim's new `/v1/refunds` and monobank's `invoice/cancel`, and find refunds
  by their reference and in the invoice's `cancelList`. acquirer-sim answers both, the monobank-style ones after
  `sim.refund-delay` (2 s); every refund of the card 4000 0000 0000 5126 is declined. merchant-sim takes the
  refund webhooks.
- Metrics: every app has `/actuator/prometheus`. The gateway counts payments and refunds by outcome, provider and
  code, risk-service's answers and webhook deliveries; risk-service counts its decisions by rule; the circuit
  breakers publish their state and calls. compose runs Prometheus and Grafana, with a provisioned dashboard; new
  host port settings `PROMETHEUS_PORT` (9090) and `GRAFANA_PORT` (3000).
- Tracing: every app traces its requests with OpenTelemetry, across HTTP and gRPC calls, and compose sends the
  traces to Jaeger (`JAEGER_PORT`, 16686). Log lines carry the trace id, and the gateway's startup log says where
  traces go. risk-service has an HTTP port now, `RISK_SERVICE_HTTP_PORT` (8094), for health and metrics.
- The API contract is 0.6.0, and acquirer-sim's describes its refunds.
- The README names JUnit 6, which Spring Boot 4.1 brings, instead of JUnit 5.

## 0.5.0 (2026-11-26)

- risk-service, a new app the gateway asks over gRPC before every charge (risk-api holds the contract,
  `risk.proto`). It declines a card from a blocked range, an amount over its currency's limit, and a card's
  eleventh payment within ten minutes, counted in Redis once per Idempotency-Key.
- A payment risk-service declines is `DECLINED` with `risk_declined`, and no provider sees it. risk-service gets a
  keyed hash of the card number, never the number.
- When risk-service doesn't answer within 300 ms or is down, payments go ahead by default and the log says so;
  `GATEWAY_RISK_WHEN_UNAVAILABLE=decline` fails them with `risk_unavailable` instead. A circuit breaker stops
  payments waiting for a risk-service that keeps failing.
- The API contract (0.5.0) names the two new codes.
- compose runs risk-service, and the pipeline builds its image.
- acquirer-sim's webhook test answers each webhook before the test can stop its receiver, so no false "not
  delivered" warning is logged.

## 0.4.0 (2026-11-26)

- Failover: `GATEWAY_PROVIDERS` lists the providers new payments go to, the preferred one first, and replaces
  `GATEWAY_PROVIDER`. A payment goes on to the next provider only when one never got the charge (no connection, or
  429), so no card is charged twice; with none left it fails as `provider_unavailable`.
- A circuit breaker per provider (Resilience4j): a provider whose charges keep getting no answer or not getting
  through is skipped for 30 s, then two trial charges show whether it is back. A payment that finds every provider
  skipped fails at once instead of waiting on them.
- Gateway instances share open circuits through Redis. Redis is optional: without it each instance goes by its own,
  and a payment never waits on it for more than 500 ms.
- The acquirer-sim connector takes a 429 as a charge turned away unread, as the monobank one already did.
- acquirer-sim also answers the part of monobank's acquiring API the gateway uses, under `/api/merchant`, with
  webhooks signed as monobank signs them.
- compose runs Redis and monobank-sim, a second acquirer-sim playing monobank; new payments go to it first, and to
  acquirer-sim when it is stopped. A new host port setting, `MONOBANK_SIM_PORT` (8093).

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
