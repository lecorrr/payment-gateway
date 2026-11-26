# Architecture

Four Spring Boot apps in one Maven build. **gateway-service** is the merchant API and owns the payments and their
refunds: it asks risk-service about each new payment, charges cards with acquirer-sim and with monobank's acquiring
API, moving a payment on to the other one when a provider can't take it, and refunds through the provider that
charged. **risk-service** decides, over gRPC, whether a payment
may go ahead, and keeps nothing but each card's recent payments in Redis. **acquirer-sim** plays a card acquirer
behind the gateway, with its own API, error format and naming (snake_case), the way a real provider would, and under
`/api/merchant` the part of monobank's API the gateway uses, so a second copy of it can play monobank;
**merchant-sim** plays a shop in front of it, taking the gateway's webhooks and the customer's return from 3-D
Secure. **risk-api** is no app: it holds the gRPC contract, risk.proto, and the Java code generated from it, which
the gateway and risk-service both build on. Apart from that contract the apps share no code: merchant-sim checks
webhook signatures with its own code, and a test in each app checks the same published signature example, so the two
sides can't drift apart. In compose, Prometheus, Grafana and Jaeger watch all of them (see Observability). On
Kubernetes, one Helm chart runs them, an environment per namespace (see Kubernetes). The GitLab pipeline builds
each image once, checks it, runs it on a cluster of its own and only then releases it (see Pipeline).

## Modules of gateway-service

| Module     | Owns                                                                                    | May use                        |
|------------|-----------------------------------------------------------------------------------------|--------------------------------|
| `common`   | currencies, `ErrorCode` and `GatewayException`, problem details, the clock              | nothing                        |
| `card`     | card number (Luhn, brand, masking), expiry, CVC; no framework imports                   | common                         |
| `webhook`  | merchants' webhook endpoints and secrets, the outbox, signing, the dispatcher           | common                         |
| `merchant` | merchants, the API key filter, the current merchant, the demo merchant                  | common, webhook                |
| `provider` | the connector contract (`ProviderConnector`, `ChargeRequest`, `ChargeLookup`, `ProviderResult`, `RefundRequest`, `RefundLookup`, `ProviderRefundResult`), the connectors by name, the routing between them (`ProviderRouter`), `ChargeNotifications` and `RefundNotifications`; one package per provider below it (`acquirersim`, `monobank`) | common, card |
| `risk`     | the client of risk-service (`RiskCheck`, `RiskVerdict`) and the circuit breaker in front of it | common                         |
| `payment`  | the API controllers, `PaymentService` and `RefundService`, the payments and refunds tables, fingerprints and card tokens, the checkers, the 3-D Secure return, the payment and refund metrics | common, card, merchant, provider, risk, webhook, api |

`api` is the interface and DTOs generated from
[payment-gateway.yaml](gateway-service/src/main/resources/static/openapi/payment-gateway.yaml), the webhook body
included. `LayeringTest` checks the table on every build, and also that `card` imports no Spring or Jakarta classes,
that no module imports a provider's package (Spring wires the connector in, so a new provider touches nothing else),
and that only `risk` imports the gRPC contract. `webhook` knows nothing of payments: it stores and sends whatever
JSON body `payment` gives it.

## A payment, step by step

1. `ApiKeyFilter` hashes `X-Api-Key` (SHA-256) and looks the merchant up; no match is 401. Paths are public only by
   an allow list (health, docs, `/3ds/`, providers' webhooks under `/providers/`), so a new endpoint is protected by
   default.
2. Bean validation from the contract checks the request's shape; `PaymentMapper` then parses the card and the
   return URL: a number that fails the Luhn check is 422 `CARD_INVALID`, a return URL that is no URL 422
   `RETURN_URL_INVALID`.
3. `PaymentService` computes the request fingerprint. A payment with this `Idempotency-Key` already there is
   returned as a replay if the fingerprints match, or refused with 422 `IDEMPOTENCY_KEY_REUSED`. An expired card is
   422 `CARD_EXPIRED`. Requests refused here store nothing, so the key can be used again.
4. `RiskCheck` asks risk-service about the payment (see risk-service below). A payment it declines is stored as
   `DECLINED` with `risk_declined` and never charged; with `gateway.risk.when-unavailable=decline`, one it can't
   check is stored as `FAILED` with `risk_unavailable`. Either names the first provider of the order, as a payment
   no provider could take does.
5. The payment is inserted as `PROCESSING` with its first check due (`INSERT ... ON CONFLICT DO NOTHING`; losing a
   race with a parallel request turns into a replay of the winner's payment).
6. The provider the router picks (see Routing) is called outside any transaction, with the payment's id as the
   reference and the gateway's `/3ds/return/{id}` as the return URL; the payment keeps the provider's name. What
   came back decides the status:

   | What happened                                          | Outcome          | Payment                                  |
   |--------------------------------------------------------|------------------|------------------------------------------|
   | approved                                               | APPROVED         | `SUCCEEDED`                              |
   | declined                                               | DECLINED         | `DECLINED` with the provider's code      |
   | requires_action with a challenge URL                   | REQUIRES_ACTION  | `REQUIRES_ACTION` with `nextAction`      |
   | still processing                                       | PENDING          | stays as it is; the provider's id is kept |
   | other 4xx: the request itself was refused              | FAILED           | `FAILED`, `provider_rejected_request`    |
   | connection refused, unknown host, connect timeout, 429 | FAILED           | `FAILED`, `provider_unavailable`         |
   | 5xx, read timeout, dropped connection, unreadable body | UNKNOWN          | stays `PROCESSING`                       |

   `provider_unavailable` means the provider never got the charge, so the payment first goes to the next provider,
   and fails only when none is left.

7. The change is written with `UPDATE ... WHERE status IN ('PROCESSING', 'REQUIRES_ACTION')` (only `PROCESSING` for
   the move to `REQUIRES_ACTION`), in one transaction with its webhook event. Whichever of the request, the checker,
   the customer's return and a provider's webhook writes a change first, that one stands, and only it gets an event.

`PendingPaymentChecker` runs every `check-interval`. It claims the payments whose next check is due with
`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)`, which also moves their next check forward
(`check-interval` for `PROCESSING`, `challenge-check-interval` for `REQUIRES_ACTION`), so instances don't wait for
each other and skip what another one has just claimed. It looks each one up with the provider that charged it, by
the payment's id and the provider's own id when there is one: an outcome is recorded
as above; no record of the charge after `give-up-after` (10 minutes) is `FAILED`, `provider_no_record`; a failed
lookup changes nothing, since it says nothing about the charge, and the payment is asked about again.

Only states that can't be wrong are written: a payment is never marked `FAILED` while the card may have been
charged, and a challenge is never given up by the gateway while the bank may still approve it.

## A refund, step by step

1. `RefundsController` takes `POST /api/v1/payments/{id}/refunds` with an amount and an `Idempotency-Key`; the
   fingerprint covers the payment and the amount. A refund with this key already there is replayed (200) or, for
   another payment or amount, refused with 422 `IDEMPOTENCY_KEY_REUSED`.
2. In one transaction, `RefundService` locks the payment's row (`SELECT ... FOR UPDATE`) and then checks: the key
   again, since a request with the same key may have stored its refund while this one waited for the lock; that the
   payment is the merchant's (404 otherwise) and `SUCCEEDED` (409 `PAYMENT_NOT_REFUNDABLE`); and that the amount fits
   in what is left, the payment's amount less its `PROCESSING` and `SUCCEEDED` refunds (409
   `REFUND_AMOUNT_TOO_LARGE`). The refund is inserted as `PROCESSING` with its first check due, and the transaction
   commits, letting the next refund of the payment in.
3. The provider that charged the payment is called outside any transaction, with the charge's id at the provider
   and the refund's id as the reference: acquirer-sim's `POST /v1/refunds`, or monobank's `invoice/cancel` with the
   refund's id as `extRef`. The answer maps as a charge's does:

   | What happened                                          | Outcome   | Refund                                |
   |--------------------------------------------------------|-----------|---------------------------------------|
   | refunded                                               | SUCCEEDED | `SUCCEEDED`                           |
   | declined                                               | FAILED    | `FAILED` with `refund_declined`       |
   | still processing                                       | PENDING   | stays `PROCESSING`                    |
   | other 4xx: the request itself was refused              | FAILED    | `FAILED`, `provider_rejected_request` |
   | connection refused, unknown host, connect timeout, 429 | FAILED    | `FAILED`, `provider_unavailable`      |
   | 5xx, read timeout, dropped connection, unreadable body | UNKNOWN   | stays `PROCESSING`                    |

4. The outcome is written with `UPDATE ... WHERE status = 'PROCESSING'`, in one transaction with its webhook event
   and, for `SUCCEEDED`, the payment's `refunded_amount`. Whichever of the request, the checker and a provider's
   webhook writes first, that one stands. A `FAILED` refund no longer counts against the payment, so its amount can
   be asked for again with a new key.

`PendingRefundChecker` does for refunds what the payments' checker does, with the same timings: it claims the due ones
with `SKIP LOCKED`, looks each up with its provider by the refund's id (acquirer-sim's `GET /v1/refunds?reference=`, or
the `cancelList` of monobank's invoice, by `extRef`), and makes a refund `FAILED`, `provider_no_record`, only when the
provider still has no record of it after `give-up-after`.

Without the lock, two refunds sent at once would both read the same amount left and both be stored. With it the
second waits for the first to commit and then counts the first's refund too. `refunded_amount` has a constraint
(between 0 and the payment's amount) as a last guard, and refunds never change the payment's status: a refunded
payment is still a `SUCCEEDED` one, with its `refundedAmount` telling how much came back.

## Routing

`gateway.providers.order` (`GATEWAY_PROVIDERS`) lists the providers new payments go to, the preferred one first;
each of them must have its settings, by a startup check. For a new payment, `ProviderRouter` gives the first
provider in that order whose circuit lets a charge through, and the payment is stored with it. When that provider
never got the charge (`FAILED`, `provider_unavailable`: no connection, or 429), the router gives the next one, and
the payment is moved to it in the database before it is charged there (`UPDATE ... WHERE status = 'PROCESSING' AND
provider_reference IS NULL`), so the payment always names the provider that may have its charge. Every other outcome
stays with the provider that had the charge, a timeout or a 5xx included: the card may have been charged there, and
charging it elsewhere too could charge it twice. With no provider left the payment is `FAILED`,
`provider_unavailable`; when every circuit is open, at once, without a call.

Each provider has a circuit breaker (Resilience4j), counting its last `window-size` charges. A charge counts as
failed when it got no answer (UNKNOWN) or never got through; declines and refused requests are the provider doing
its job. Once at least `minimum-calls` are counted and `failure-rate-percent` of them failed, the circuit opens: the
provider gets no new payments for `open-for`, then `trial-calls` charges decide whether it closes or stays open for
another `open-for`. Lookups don't go through the circuit, so payments a provider already has are settled with it as
before.

Instances share open circuits through Redis. When a circuit opens, the provider is marked under
`payment-gateway:open-circuit:<name>` for `open-for`, and other instances skip it without failing payments of their
own against it first; the mark goes when the circuit closes. Redis is optional: when it can't be reached, each
instance goes by its own circuits, says so once in its log, and asks Redis again after 30 s, so Redis being down
costs payments at most its 500 ms timeout once every 30 s, and never fails one.

## risk-service

The gateway's `RiskCheck` calls `RiskService.Assess` of [risk.proto](risk-api/src/main/proto/risk/v1/risk.proto)
with a deadline of `gateway.risk.deadline` (300 ms). It sends the payment's id, the merchant, the amount and
currency, the first six digits of the card number, and the card token: an HMAC-SHA256 of the card number with the
fingerprint key, the same for every payment with the card, from which the number can't be worked back. The attempt
id, the merchant's id and its Idempotency-Key, is the same for every try of one request.

risk-service applies three rules, in order: a card range in `risk.blocked-bins` (400009 for the demo); an amount
over `risk.amount-limits` for its currency (100 000.00 UAH, 3 000.00 USD or EUR); and more than
`risk.velocity.max-payments` (10) payments with the card within `risk.velocity.window` (10 minutes). The last is
counted in Redis, in a sorted set per card holding each attempt once, scored by when it was first counted. One Lua
script drops the attempts older than the window, adds this one unless it is there, keeps the set for a window more
and counts, so every instance of risk-service counts alike and a retry counts once. Every payment is counted,
declined ones too. When Redis can't be reached, the velocity rule is left out (logged once, and Redis is asked again
after 30 s) while the other two still apply; Redis's timeouts (100 ms) are well inside the gateway's deadline. A
request that is no payment the rules can judge is refused with `INVALID_ARGUMENT`, naming the field.

What the gateway does without an answer (an error, the deadline, an answer without a decision) is
`gateway.risk.when-unavailable`: `allow`, the default, lets the payment go ahead unchecked, with a warning in the
log; `decline` fails it with `risk_unavailable`. A circuit breaker counts risk-service's last ten answers: once at
least five came and half of them were errors or late, payments stop waiting for it for `gateway.risk.open-for` (30
s), and then two trial calls decide whether it is back. The channel is plaintext inside the compose network;
`spring.grpc.client.channel.risk.ssl` turns TLS on.

## 3-D Secure

1. The merchant creates the payment; the gateway charges the card with `/3ds/return/{id}` as the return URL.
2. acquirer-sim answers `requires_action` with an `action_url`. The gateway stores `REQUIRES_ACTION`, answers the
   merchant with `nextAction.url`, and writes a `payment.requires_action` webhook.
3. The merchant sends the customer's browser to that URL, where the customer passes or fails the challenge.
4. acquirer-sim sends the browser back to the gateway's `/3ds/return/{id}`.
5. The gateway looks the charge up, records the outcome with its webhook, and sends the browser on to the
   merchant's `returnUrl` with `?paymentId=...` added.

The browser's return is only a signal to ask: `GET /3ds/return/{id}` needs no API key, trusts nothing the browser
brings, and settles the payment from the provider's own answer, so a forged return can't make a payment succeed.
A customer who never comes back leaves the payment to the checker, which looks it up every
`challenge-check-interval`; acquirer-sim declines a challenge left open for `sim.challenge-ttl` (15 minutes) with
`authentication_expired`, and the next lookup settles the payment.

## monobank

`MonobankConnector` charges with monobank's `payment-direct`, which takes the card's details; monobank opens it to
merchants with a PCI DSS certificate. The payment's id goes as `merchantPaymInfo.reference`, the amount in minor
units, the currency as its ISO 4217 number. monobank answers with an invoice:

| Invoice                                 | Outcome          | Why                                                         |
|-----------------------------------------|------------------|-------------------------------------------------------------|
| `success`                               | APPROVED         |                                                             |
| `processing` with a `tdsUrl`            | REQUIRES_ACTION  | the customer goes to `tdsUrl` and comes back to `/3ds/return/{id}` |
| `processing`, `created`                 | PENDING          | monobank is still working on it; its invoice id is kept     |
| `failure`                               | DECLINED         | `errCode` turned into the gateway's code, by `MonobankDeclineCodes` |
| `expired`                               | DECLINED         | the customer never finished 3-D Secure: `authentication_expired` |
| `hold`, `reversed`, anything new        | PENDING          | can't follow the gateway's debits; logged for someone to look |

`payment-direct` names a failure's reason in words only, so the connector asks the invoice's status for its `errCode`.
Lookups ask the invoice status by its id. A charge whose answer was lost has no invoice id at the gateway, so the
lookup searches monobank's statement since the charge (less a minute, for the clocks) for the payment's id first;
not there is NOT_FOUND, and a statement that can't be read is UNKNOWN, never NOT_FOUND, so it can't make the
gateway give up on a charge that went through.

With `gateway.providers.monobank.webhook-url` set, monobank reports each change of the invoice to
`POST /providers/monobank/webhooks`. The report counts only if its `X-Sign`, an ECDSA (P-256, SHA-256) signature over
the body's exact bytes, matches monobank's public key; the key is fetched from `/api/merchant/pubkey` once and
again, at most every 10 minutes, when a signature doesn't match. Even a signed report is only a reason to ask:
through `ChargeNotifications`, the payment is looked up with monobank again, so a report that comes late or out of
order can't set a payment back. The same report goes to `RefundNotifications`, which looks up each of the payment's
refunds still `PROCESSING` in the invoice's `cancelList`, so a report about a refund settles it at once; monobank-sim
sends one when a refund finishes, and without a report the checker finds the outcome.

## Webhooks

- **Outbox.** `WebhookOutbox.add` runs inside the transaction of the payment or refund change and refuses to run
  outside one, so an event exists exactly when its change does: no event for a change that rolled back, no change
  without its event. A refund's event is stored under its payment's id. The body is stored as the exact text that will
  be signed and sent (`text`, not `jsonb`, which would reorder it). A merchant without an endpoint gets no events.
- **Dispatcher.** `WebhookDispatcher` runs every `dispatch-interval`, claims due events with the same
  `SKIP LOCKED` lease as the checker, and sends each one with `WebhookSender`. The lease is long enough for the whole
  batch to time out, so another instance doesn't send them meanwhile; an instance that dies mid-send leaves its
  events to the next claim. Delivery is therefore at least once, and receivers deduplicate by `webhook-id`.
- **Signing.** Standard Webhooks: `webhook-id`, `webhook-timestamp` (of this attempt), and `webhook-signature`,
  `v1,` and the Base64 HMAC-SHA256 of `{id}.{timestamp}.{body}` keyed with the `whsec_` secret's bytes.
- **Answers.** 2xx is delivered. Redirects are not followed (an endpoint that moved should be updated) and count as
  a failure, like timeouts, refused connections and other statuses: the event is tried again after each of
  `retry-delays` in turn, with up to a tenth more at random, then given up. 410 Gone is given up at once.

## Card data

- **Stored:** the brand, the first six and the last four digits, which PCI DSS allows to be shown.
- **Never stored:** the number, the CVC and the holder's name. The fingerprint is an HMAC of the whole request
  except the CVC, with a secret key (PCI DSS allows keyed hashes of the full card number); the CVC must not be kept
  in any form.
- **Never logged or sent:** every type that holds card data masks itself in `toString()`, validation errors name
  the field and the rule but not the value, and Jackson's parse errors are not passed on. The generated request DTO
  can't mask itself, so it is never logged, and `application.yml` keeps Spring's request body logging off. Webhooks
  carry the payment as the API shows it, with the card summary only. `CreatePaymentIT` checks the database rows and
  the log output for the card number and the holder, `WebhookDeliveryIT` the webhook bodies.

## Adding a provider

1. A new package under `provider`, with a `ProviderConnector` and its properties record. Its `name()` is stored with
   each payment it charges; `missingSettings()` names what it still needs, and a provider in
   `gateway.providers.order` must need nothing.
2. Map the provider's answers to `ProviderResult`. `FAILED` only when the card was certainly not charged, and
   `provider_unavailable` only when the provider never got the request, since that sends the payment to the next
   provider; anything in doubt is `UNKNOWN`, and the checker settles it with `lookup`; a charge the provider is still
   working on is `PENDING`. A 3-D Secure challenge is `REQUIRES_ACTION` with the URL to send the customer to; the
   provider must send the browser back to the `returnUrl` of the `ChargeRequest`. Decline codes are the gateway's, as
   in the API contract.
3. A lookup gets the payment's id, the provider's own id when the gateway has one, and when the charge was sent.
4. A provider's webhook, if it has them, checks the provider's signature and calls `ChargeNotifications`, and
   `RefundNotifications` when it is about refunds.
5. `refund` and `lookupRefund` map to `ProviderRefundResult` by the same rules: `FAILED` only when no money went
   back, `UNKNOWN` when in doubt. The refund's id goes to the provider as its reference, so a lookup can find it
   without anything the answer brought.
6. Test the failure modes with WireMock, as `AcquirerSimConnectorTest`, `AcquirerSimRefundTest`,
   `MonobankConnectorTest` and `MonobankRefundTest` do.

`ProviderConnectors` holds every connector by name, and those of `gateway.providers.order` in that order; a payment is
always looked up with the provider that charged it, so changing the order leaves payments in flight with their own.

## Observability

- **Metrics.** Each app has Micrometer with the Prometheus registry; Prometheus scrapes `/actuator/prometheus`
  every 5 s, one job per compose service, so acquirer-sim and monobank-sim, one app in two containers, stay apart.
  `PaymentMetrics` counts a payment or refund where its outcome is stored, after the guarded update, so each one
  counts once whoever settled it; `RiskCheck` counts risk-service's answers, `WebhookDispatcher` each delivery's
  outcome, risk-service its decisions by rule. The circuit breakers are published by Resilience4j's
  `TaggedCircuitBreakerMetricsPublisher`, for the providers and for risk-service. Labels take only short, fixed
  lists of values (statuses, provider names, the gateway's codes), never ids, so the series stay few.
- **Latencies.** Spring times HTTP requests in and out (`RestClient` from the auto-configured builder, which every
  connector and the webhook sender use) and the gRPC calls, and the gateway keeps them as histograms, so Grafana
  can show percentiles across instances.
- **Traces.** Micrometer Tracing with the OpenTelemetry bridge (`spring-boot-starter-opentelemetry`). The trace
  context travels in W3C `traceparent`: through the same `RestClient` builder, and through gRPC by the global
  client and server interceptors Spring Boot registers. Each app sends its spans over OTLP/HTTP to Jaeger, and only
  when `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is set, so tests export nothing. Sampling is 1.0,
  as a demo can afford. Work the schedulers start (the checkers, the webhook dispatcher) begins traces of its own,
  so a webhook's trace starts at the dispatcher, not at the payment that caused it.
- **Logs.** Each line carries `[traceId-spanId]` while a trace is open, so a payment's lines in the gateway and
  risk-service can be found by one id.
- **Grafana** is provisioned from files: Prometheus as its data source, and the dashboard, read-only (a changed
  copy is saved under another name). Anonymous visitors may look, as a demo on localhost can allow.

In compose the actuator answers on the API port, as health already did. On Kubernetes it has a port of its own,
8081 (`management.server.port`), which the Ingress doesn't route and the network policies open only to Prometheus
and `helm test`.

## Kubernetes

[deploy/helm/payment-gateway](deploy/helm/payment-gateway) is one chart for every environment, installed once per
namespace. Its objects are named after the apps, as compose's services are, so the apps find each other by the same
names: `postgres`, `redis`, `risk-service`. An environment's values file holds only what it changes: replicas,
autoscaling and disruption budgets, whether the simulators, PostgreSQL and Redis run in the namespace, the Ingress
hosts, where traces go. `values.schema.json` refuses a value the chart doesn't know, and a few checks in the
templates refuse what doesn't fit together, such as a disruption budget no node drain could ever meet.

Every pod of an app:

- runs as its image's user, never root, with a read-only root file system (`/tmp` is an emptyDir), no Linux
  capabilities and the runtime's seccomp profile: the restricted Pod Security Standard, which the namespaces of
  `deploy/k3d` enforce;
- gets no service account token, since nothing here calls the Kubernetes API, and no service link variables: with
  them, the Service `redis` would set `REDIS_PORT=tcp://...` in every pod, where the gateway reads its own setting;
- is probed on its management port (risk-service's HTTP port has nothing else, so it is 8094 there). The startup
  probe gives the JVM three minutes; liveness and readiness ask Spring Boot's health groups;
- stops in two steps: the preStop hook waits 10 s while the pod leaves the Service and the Ingress's backends, then
  Spring finishes the requests it has, for up to 30 s. The gateway and risk-service roll out with
  `maxUnavailable: 0`, so a rollout never has fewer ready pods than before it.

The Secret is made outside the chart (`deploy.sh` makes one of random keys on k3d; in production the cluster's
secret store would), and the chart only reads it, so no secret is in a values file or in Helm's release history.
It stays after `helm uninstall`, as PostgreSQL's volume does, whose password it holds.

The network policies deny everything in both directions, let every pod use the cluster's DNS, and then open what
the apps need:

| From                    | To                                                                                   |
|-------------------------|--------------------------------------------------------------------------------------|
| the ingress controller  | gateway 8090, acquirer-sim and monobank-sim 8091, merchant-sim 8092                  |
| gateway                 | postgres 5432, redis 6379, risk-service 9090, the simulators 8091, merchant-sim 8092 |
| risk-service            | redis 6379                                                                           |
| monobank-sim            | gateway 8090: its webhooks                                                           |
| `helm test`, Prometheus | every app's management port                                                          |

Outside the namespace, production adds the gateway's egress to monobank's API and the merchants' webhook endpoints
(443) and to the managed database, and both apps' to the managed Redis and the OpenTelemetry Collector
(`networkPolicy.gatewayEgress`, `riskServiceEgress`).

risk-service's Service is headless: its name resolves to every ready pod, and each gateway's gRPC channel (target
`dns:///risk-service:9090`, with Spring Boot's default `round_robin`) keeps a connection to each pod and takes turns.
risk-service ends every connection after `riskService.maxConnectionAge` (1 min; 30 s in staging), so the gateways
resolve the name again and a pod that came up after them, in a rollout or by the autoscaler, gets calls too.

The simulators keep their state in memory (charges, challenges, monobank-sim's signing key), so each runs as one
pod that a new one replaces (`Recreate`) rather than runs beside. PostgreSQL is a StatefulSet with a volume claim,
running as the alpine image's postgres user (70) with `PGDATA` a folder inside the volume, since that user may not
own the volume's top. Redis keeps nothing on disk: the open circuits and recent payment counts mean nothing after
a restart.

On k3d, `deploy.sh` builds the images from the jars and tags them with a checksum of the jars, so a new build means
new pods and the same jars change nothing; with `IMAGE_REGISTRY` and `IMAGE_TAG` it takes a registry's images
instead, such as a released version's. `k3d image import` loads them, PostgreSQL's and Redis' with them, into every
node, so the nodes pull nothing from a registry. The chart is checked twice: `ci/check-chart.sh` renders every
environment and checks the result against Kubernetes 1.35's schemas, along with values the chart must refuse, and
`deploy/k3d/smoke-test.sh` checks an environment that runs, from outside through the Ingress and from inside the
pods.

## Pipeline

[.gitlab-ci.yml](.gitlab-ci.yml) runs on GitLab's small runners (two CPUs, 8 GB), each job in a container of its
own, with a Docker-in-Docker service for the jobs that need Docker. Its jobs are gates, in the order a change goes
through them:

| Stage   | Job     | Fails when                                                                        | Runs on    |
|---------|---------|-----------------------------------------------------------------------------------|------------|
| test    | verify  | a test fails, a module's line coverage is under 80 %, or SpotBugs finds a bug pattern of medium priority or above | every push |
| test    | chart   | the chart doesn't lint, renders something Kubernetes 1.35 would refuse, or takes a value it must refuse | every push |
| test    | secrets | a commit, any commit, holds something that looks like a key, a token or a password | every push |
| package | images  | an image has a known HIGH or CRITICAL vulnerability that has a fix                | every push |
| e2e     | e2e     | the commit's images, deployed as dev to a k3d cluster, fail the smoke test         | main       |
| release | release | (it releases: the commit's images get their version's tag)                         | main       |

- **verify** runs `./mvnw verify`: the unit and integration tests, JaCoCo's coverage check, and SpotBugs with
  FindSecBugs' security rules over every module's classes (risk-api's are all generated, so it is left out).
  `spotbugs-exclude.xml` holds the four exceptions, each with its reason: the code generated from the OpenAPI
  contract, format strings in text blocks (which end their lines with `\n`, not `%n`), a list SpotBugs can't see is
  an unmodifiable copy, and the webhook retries' jitter, which needs no secure random. SpotBugs reads the libraries'
  JSpecify annotations, so a value Spring marks as possibly null must be checked before it is used.
- **secrets** runs gitleaks' default rules over the whole history, since a secret a later commit deleted is still
  in git. `.gitleaks.toml` leaves out the tests, whose keys are made up for them or published test vectors.
- **images** builds the four images from verify's jars, and Trivy scans each one's Ubuntu packages and the Java
  libraries in its jar (`ci/build-images.sh`). Only what can be fixed fails the job (`--ignore-unfixed`): a finding
  no newer version fixes yet would stop every pipeline and leave nothing to do. An exception would go into a
  `.trivyignore.yaml`, with its reason and an expiry date; there are none. On main the images are then pushed to
  the project's container registry under the commit's full SHA.
- **e2e** makes a one-node k3d cluster inside the job's Docker-in-Docker (`deploy/k3d/cluster-ci.yaml`), deploys
  exactly those images as dev, `deploy.sh` pulling them from the registry, and runs `deploy/k3d/smoke-test.sh dev`.
  The cluster's API and its load balancer are in the Docker service, which the job reaches as `docker`, so the
  smoke test sends its requests for `*.dev.localhost:8280` to `docker:8280` (`INGRESS_ADDRESS`). Whatever the
  outcome, the job keeps the cluster's state and every pod's log as artifacts (`ci/collect-e2e-logs.sh`).
- **release** gives the commit's images the version they were built as, from `pom.xml` through the jars'
  build-info, such as `:0.8.0`, as a copy of the same manifest: the released image is byte for byte the one the
  checks saw (`ci/release-images.sh`).

Every tool a job downloads comes from `ci/install-tools.sh`, in a fixed version, checked against its published
checksum before it runs.

## Decisions

- **JdbcClient and SQL, not JPA.** A handful of tables, and statements whose exact shape matters:
  `ON CONFLICT DO NOTHING`, `FOR UPDATE SKIP LOCKED`, updates guarded by the current status.
- **The payment is stored before the provider call.** An HTTP call can't be part of a database transaction; storing
  `PROCESSING` first means a crash mid-call leaves a payment for the checker, never a charge the gateway doesn't know
  about.
- **Failover only when a provider never got the charge.** After a timeout or a 5xx the card may have been charged,
  and trying the next provider could charge it twice; the checker finds out instead. The move to the next provider
  is written before the call, so the payment never has a charge at a provider it doesn't name.
- **Resilience4j's circuit breaker, without its Spring Boot starter.** The router asks each circuit for a permit and
  reports every charge's result itself: what counts as a failure is in the provider's answer, not an exception, and
  a circuit that is open sends the payment to the next provider rather than failing it.
- **Open circuits shared through Redis, but each instance decides alone.** Sharing spares the other instances from
  failing payments' first tries against a provider known to be down; keeping the circuits themselves in each
  instance means that Redis going away loses only that.
- **gRPC between the gateway and risk-service.** An internal call on every payment, with a schema both sides
  compile against, a deadline that travels with the call, and HTTP/2 connections that stay open; Spring Boot 4.1
  has the client and the server built in.
- **A payment risk-service can't check goes ahead, by default.** A risk check that is down shouldn't take card
  payments down with it; each such payment is logged, and `decline` is there for a merchant who'd rather lose the
  sale.
- **risk-service sees a card token, not the card.** It only needs to know the same card again, so it gets an HMAC
  of the number with a key it doesn't have, and never handles card data.
- **An outbox, not a webhook call in the request.** Sending from the request would either hold a transaction open
  over an HTTP call or lose the event on a crash between commit and send.
- **Standard Webhooks** for signing, rather than a scheme of the gateway's own: merchants can check signatures
  with existing libraries, and the scheme covers the id and the time, so a replayed webhook is caught.
- **Webhook secrets are stored as they are.** Signing needs the secret itself, so a hash won't do; in production it
  would be encrypted at rest with a key from a secrets manager.
- **The gateway's own decline codes.** Each connector turns its provider's codes into the same few, so a merchant's
  handling doesn't change with the provider; the provider's own reason is in the gateway's log.
- **A provider's report is a reason to ask.** A webhook from a provider, like the browser back from 3-D Secure,
  starts a lookup instead of being taken as the outcome: the provider's answer is the one fact, whatever arrives
  late, twice or out of order.
- **422 for a reused Idempotency-Key**, as the IETF Idempotency-Key draft says: retrying won't help, unlike a 409.
- **409 for a refund the payment can't take.** `PAYMENT_NOT_REFUNDABLE` and `REFUND_AMOUNT_TOO_LARGE` are about the
  payment as it is now: a payment still `PROCESSING` may succeed, and a refund that fails frees its amount.
- **The payment's row lock orders its refunds.** The check that a refund fits and its insert must not interleave
  with another refund's; a lock on the payment orders them without touching other payments, and without the
  retries serializable transactions would need.
- **Refunds go only to the provider that charged, without a circuit breaker.** Money can only go back the way it
  came, so there is no next provider for an open circuit to send a refund to; it could only fail it. A refund that
  failed because its provider was down frees its amount and can be asked for again.
- **Metrics pulled, traces pushed.** Prometheus scraping is how Spring apps are usually watched and needs nothing
  in the apps but an endpoint; traces go out over OTLP, so the tracing backend can change without the apps
  changing.
- **One chart, a values file per environment.** The templates are the same everywhere and an environment is the
  short list of what differs, checked by the chart's schema; a chart or overlay per environment would let the
  manifests themselves drift apart.
- **The chart reads a Secret, it doesn't make one.** Secrets in values files end up in git or in Helm's release
  history; a Secret made apart from the release also outlives it together with the database it opens.
- **Readiness is the app's own.** A gateway that can't reach PostgreSQL still answers, with an error, while one
  taken out of the Service doesn't answer at all; and with the database in readiness, one database outage would
  take every gateway out at once.
- **Deny by default between pods.** A pod that is taken over reaches only what its app needs, and only the gateway
  reaches the database.
- **gRPC balanced by the client.** Through a Service's virtual IP, each gateway's single HTTP/2 connection would
  take all its calls to one risk-service pod; the headless Service and connections that end after a while spread
  them over every pod, new ones too.
- **A provider's settings only when it is used.** acquirer-sim's key, like monobank's token, is needed only when
  `GATEWAY_PROVIDERS` names it: production runs without the simulator.
- **The gateway waits for its database.** Kubernetes starts the gateway and PostgreSQL side by side, so the
  migration tries to connect for about a minute (Flyway's `connect-retries`) rather than the gateway failing and
  restarting until the database is up.
- **Build once, release by tag.** The images job builds each image once and pushes it under the commit's SHA; e2e
  runs those very images, and release only adds the version's tag to the same manifest. Building again for the
  release would release an image no check ever saw.
- **A version is released once.** The release job never moves a version's tag: a later commit of the same version
  keeps only its commit tag, so `0.8.0` names one build wherever it runs.
- **Static analysis fails the build, with its exceptions written down.** A report nobody must read stops being
  read; a short exclude file, a reason for each entry, keeps the findings few enough that a new one gets looked at.
- **Image scans fail on what can be fixed.** HIGH and CRITICAL vulnerabilities with a fix stop the pipeline; one
  that no version fixes yet would stop every pipeline with nothing to do but wait, so it counts once a fix exists.
- **The end-to-end check runs on main, after the merge.** It is the slowest job, and a free account has 400 compute
  minutes a month; a merge request gets every other gate, and a commit that fails the e2e check is never released.
- **Tools in fixed versions, checked.** Each tool the pipeline downloads is a pinned version checked against its
  published checksum, so a pipeline runs the same tools next month, and a download that isn't the release fails.
- **One clock.** All timestamps come from the `Clock` bean, truncated to microseconds like PostgreSQL's, so a
  payment returned right away equals the one read back later.

## Configuration

| Setting                                         | Default                 | What it is                                                 |
|-------------------------------------------------|-------------------------|------------------------------------------------------------|
| `GATEWAY_FINGERPRINT_KEY`                       | none: required          | secret of the request fingerprints, 32+ characters         |
| `GATEWAY_PUBLIC_URL`                            | http://localhost:8090   | the gateway as browsers reach it, for the 3-D Secure return |
| `GATEWAY_DEMO_MERCHANT_API_KEY`                 | empty: no demo merchant | the demo merchant's API key, 24+ characters                |
| `GATEWAY_DEMO_MERCHANT_WEBHOOK_URL`, `_SECRET`  | empty: no webhooks      | the demo merchant's webhook endpoint and `whsec_` secret   |
| `ACQUIRER_SIM_URL`, `ACQUIRER_SIM_API_KEY`      | localhost:8091, empty: not configured | where acquirer-sim is, and its key; needed when `GATEWAY_PROVIDERS` names it |
| `GATEWAY_PROVIDERS`                             | acquirer-sim            | the providers new payments go to, the preferred one first  |
| `REDIS_HOST`, `REDIS_PORT`                      | localhost, 6379         | Redis, to share open circuits between instances; optional  |
| `gateway.providers.circuit-breaker.open-for`    | 30s                     | how long a failing provider is skipped; Routing has the rest |
| `RISK_SERVICE_TARGET`                           | static://localhost:9090 | where risk-service is: `spring.grpc.client.channel.risk.target` |
| `GATEWAY_RISK_ENABLED`                          | true                    | whether new payments are checked by risk-service           |
| `GATEWAY_RISK_WHEN_UNAVAILABLE`                 | allow                   | allow or decline a payment risk-service can't check        |
| `gateway.risk.deadline`, `gateway.risk.open-for` | 300ms, 30s             | how long an answer may take; how long a failing risk-service is left alone |
| `RISK_SERVICE_PORT`                             | 9090                    | risk-service's gRPC port                                   |
| `RISK_SERVICE_HTTP_PORT`                        | 8094                    | risk-service's HTTP port, for health and metrics           |
| `risk.velocity.max-payments`, `.window`         | 10, 10m                 | risk-service: the payments one card may make in the window |
| `risk.amount-limits`                            | UAH 10000000, USD and EUR 300000 | risk-service: the most one payment may be, in minor units |
| `risk.blocked-bins`                             | 400009                  | risk-service: the card ranges it declines                  |
| `GATEWAY_MONOBANK_TOKEN`                        | empty: not configured   | the merchant's monobank token (X-Token); a test token works |
| `GATEWAY_MONOBANK_WEBHOOK_URL`                  | empty: no webhooks      | this gateway's `/providers/monobank/webhooks`, as monobank reaches it |
| `MONOBANK_URL`                                  | https://api.monobank.ua | where monobank's API is                                    |
| `gateway.payments.first-check-after`            | 30s                     | the first lookup of a payment or refund left `PROCESSING`  |
| `gateway.payments.check-interval`               | 10s                     | how often the checkers run                                 |
| `gateway.payments.challenge-check-interval`     | 1m                      | how often a payment waiting for its challenge is looked up |
| `gateway.payments.give-up-after`                | 10m                     | when a charge or refund the provider has no record of fails |
| `gateway.providers.acquirer-sim.read-timeout`   | 5s                      | longer than the connect timeout, by a startup check        |
| `gateway.providers.monobank.read-timeout`       | 10s                     | the same for monobank                                      |
| `gateway.webhooks.dispatch-interval`            | 1s                      | how often the outbox is looked at                          |
| `gateway.webhooks.read-timeout`                 | 15s                     | how long a merchant's endpoint has to answer               |
| `gateway.webhooks.retry-delays`                 | 5s, 5m, 30m, 2h, 5h, 10h | the waits before each retry; then the event is given up   |
| `SIM_PUBLIC_URL`, `sim.challenge-ttl`           | localhost:8091, 15m     | acquirer-sim as browsers reach it; how long a challenge lasts |
| `sim.refund-delay`                              | 2s                      | how long acquirer-sim's monobank-style refunds stay processing |
| `MERCHANT_SIM_WEBHOOK_SECRET`                   | none: required          | merchant-sim's copy of the demo merchant's webhook secret  |
| `MERCHANT_SIM_FAIL_FIRST`                       | 0                       | deliveries of each webhook merchant-sim answers 503 first  |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | empty: none sent | where every app sends its traces; compose: Jaeger's `/v1/traces` |
| `MANAGEMENT_SERVER_PORT`                        | the app's own port      | where the actuator answers; the chart: 8081                |
| `spring.flyway.connect-retries`, `-interval`    | 12, 5s                  | how long the gateway's migration waits for the database    |
| `spring.grpc.server.keepalive.connection.max-age` | none                  | risk-service: when a connection ends; the chart: 1m        |
| `management.tracing.sampling.probability`       | 1.0                     | the share of requests traced                               |
| `GRAFANA_PORT`, `PROMETHEUS_PORT`, `JAEGER_PORT` | 3000, 9090, 16686      | compose: the host ports of the three                       |

Every settings record checks its values at startup and names the setting that is wrong.
