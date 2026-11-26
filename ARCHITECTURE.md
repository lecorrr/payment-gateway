# Architecture

Four Spring Boot apps in one Maven build. **gateway-service** is the merchant API and owns the payments: it asks
risk-service about each new payment, and charges cards with acquirer-sim and with monobank's acquiring API, moving a
payment on to the other one when a provider can't take it. **risk-service** decides, over gRPC, whether a payment
may go ahead, and keeps nothing but each card's recent payments in Redis. **acquirer-sim** plays a card acquirer
behind the gateway, with its own API, error format and naming (snake_case), the way a real provider would, and under
`/api/merchant` the part of monobank's API the gateway uses, so a second copy of it can play monobank;
**merchant-sim** plays a shop in front of it, taking the gateway's webhooks and the customer's return from 3-D
Secure. **risk-api** is no app: it holds the gRPC contract, risk.proto, and the Java code generated from it, which
the gateway and risk-service both build on. Apart from that contract the apps share no code: merchant-sim checks
webhook signatures with its own code, and a test in each app checks the same published signature example, so the two
sides can't drift apart.

## Modules of gateway-service

| Module     | Owns                                                                                    | May use                        |
|------------|-----------------------------------------------------------------------------------------|--------------------------------|
| `common`   | currencies, `ErrorCode` and `GatewayException`, problem details, the clock              | nothing                        |
| `card`     | card number (Luhn, brand, masking), expiry, CVC; no framework imports                   | common                         |
| `webhook`  | merchants' webhook endpoints and secrets, the outbox, signing, the dispatcher           | common                         |
| `merchant` | merchants, the API key filter, the current merchant, the demo merchant                  | common, webhook                |
| `provider` | the connector contract (`ProviderConnector`, `ChargeRequest`, `ChargeLookup`, `ProviderResult`), the connectors by name, the routing between them (`ProviderRouter`), `ChargeNotifications`; one package per provider below it (`acquirersim`, `monobank`) | common, card |
| `risk`     | the client of risk-service (`RiskCheck`, `RiskVerdict`) and the circuit breaker in front of it | common                         |
| `payment`  | the API controller, `PaymentService`, the payments table, fingerprints and card tokens, the checker, the 3-D Secure return | common, card, merchant, provider, risk, webhook, api |

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
order can't set a payment back.

## Webhooks

- **Outbox.** `WebhookOutbox.add` runs inside the transaction of the payment change and refuses to run outside
  one, so an event exists exactly when its change does: no event for a change that rolled back, no change without
  its event. The body is stored as the exact text that will be signed and sent (`text`, not `jsonb`, which would
  reorder it). A merchant without an endpoint gets no events.
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
4. A provider's webhook, if it has them, checks the provider's signature and calls `ChargeNotifications`.
5. Test the failure modes with WireMock, as `AcquirerSimConnectorTest` and `MonobankConnectorTest` do.

`ProviderConnectors` holds every connector by name, and those of `gateway.providers.order` in that order; a payment is
always looked up with the provider that charged it, so changing the order leaves payments in flight with their own.

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
- **One clock.** All timestamps come from the `Clock` bean, truncated to microseconds like PostgreSQL's, so a
  payment returned right away equals the one read back later.

## Configuration

| Setting                                         | Default                 | What it is                                                 |
|-------------------------------------------------|-------------------------|------------------------------------------------------------|
| `GATEWAY_FINGERPRINT_KEY`                       | none: required          | secret of the request fingerprints, 32+ characters         |
| `GATEWAY_PUBLIC_URL`                            | http://localhost:8090   | the gateway as browsers reach it, for the 3-D Secure return |
| `GATEWAY_DEMO_MERCHANT_API_KEY`                 | empty: no demo merchant | the demo merchant's API key, 24+ characters                |
| `GATEWAY_DEMO_MERCHANT_WEBHOOK_URL`, `_SECRET`  | empty: no webhooks      | the demo merchant's webhook endpoint and `whsec_` secret   |
| `ACQUIRER_SIM_URL`, `ACQUIRER_SIM_API_KEY`      | localhost:8091, none    | where acquirer-sim is, and its key                         |
| `GATEWAY_PROVIDERS`                             | acquirer-sim            | the providers new payments go to, the preferred one first  |
| `REDIS_HOST`, `REDIS_PORT`                      | localhost, 6379         | Redis, to share open circuits between instances; optional  |
| `gateway.providers.circuit-breaker.open-for`    | 30s                     | how long a failing provider is skipped; Routing has the rest |
| `RISK_SERVICE_TARGET`                           | static://localhost:9090 | where risk-service is: `spring.grpc.client.channel.risk.target` |
| `GATEWAY_RISK_ENABLED`                          | true                    | whether new payments are checked by risk-service           |
| `GATEWAY_RISK_WHEN_UNAVAILABLE`                 | allow                   | allow or decline a payment risk-service can't check        |
| `gateway.risk.deadline`, `gateway.risk.open-for` | 300ms, 30s             | how long an answer may take; how long a failing risk-service is left alone |
| `RISK_SERVICE_PORT`                             | 9090                    | risk-service's gRPC port                                   |
| `risk.velocity.max-payments`, `.window`         | 10, 10m                 | risk-service: the payments one card may make in the window |
| `risk.amount-limits`                            | UAH 10000000, USD and EUR 300000 | risk-service: the most one payment may be, in minor units |
| `risk.blocked-bins`                             | 400009                  | risk-service: the card ranges it declines                  |
| `GATEWAY_MONOBANK_TOKEN`                        | empty: not configured   | the merchant's monobank token (X-Token); a test token works |
| `GATEWAY_MONOBANK_WEBHOOK_URL`                  | empty: no webhooks      | this gateway's `/providers/monobank/webhooks`, as monobank reaches it |
| `MONOBANK_URL`                                  | https://api.monobank.ua | where monobank's API is                                    |
| `gateway.payments.first-check-after`            | 30s                     | the first lookup of a payment left `PROCESSING`            |
| `gateway.payments.check-interval`               | 10s                     | how often the checker runs                                 |
| `gateway.payments.challenge-check-interval`     | 1m                      | how often a payment waiting for its challenge is looked up |
| `gateway.payments.give-up-after`                | 10m                     | when a charge the provider has no record of fails          |
| `gateway.providers.acquirer-sim.read-timeout`   | 5s                      | longer than the connect timeout, by a startup check        |
| `gateway.providers.monobank.read-timeout`       | 10s                     | the same for monobank                                      |
| `gateway.webhooks.dispatch-interval`            | 1s                      | how often the outbox is looked at                          |
| `gateway.webhooks.read-timeout`                 | 15s                     | how long a merchant's endpoint has to answer               |
| `gateway.webhooks.retry-delays`                 | 5s, 5m, 30m, 2h, 5h, 10h | the waits before each retry; then the event is given up   |
| `SIM_PUBLIC_URL`, `sim.challenge-ttl`           | localhost:8091, 15m     | acquirer-sim as browsers reach it; how long a challenge lasts |
| `MERCHANT_SIM_WEBHOOK_SECRET`                   | none: required          | merchant-sim's copy of the demo merchant's webhook secret  |
| `MERCHANT_SIM_FAIL_FIRST`                       | 0                       | deliveries of each webhook merchant-sim answers 503 first  |

Every settings record checks its values at startup and names the setting that is wrong.
