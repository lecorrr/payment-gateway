# Architecture

Three Spring Boot apps in one Maven build. **gateway-service** is the merchant API and owns the payments;
**acquirer-sim** plays a card acquirer behind it, with its own API, error format and naming (snake_case), the way a
real provider would; **merchant-sim** plays a shop in front of it, taking the gateway's webhooks and the customer's
return from 3-D Secure. The three share no code: merchant-sim checks webhook signatures with its own code, and a
test in each app checks the same published signature example, so the two sides can't drift apart.

## Modules of gateway-service

| Module     | Owns                                                                                    | May use                        |
|------------|-----------------------------------------------------------------------------------------|--------------------------------|
| `common`   | currencies, `ErrorCode` and `GatewayException`, problem details, the clock              | nothing                        |
| `card`     | card number (Luhn, brand, masking), expiry, CVC; no framework imports                   | common                         |
| `webhook`  | merchants' webhook endpoints and secrets, the outbox, signing, the dispatcher           | common                         |
| `merchant` | merchants, the API key filter, the current merchant, the demo merchant                  | common, webhook                |
| `provider` | the connector contract (`ProviderConnector`, `ChargeRequest`, `ProviderResult`); one package per provider below it | common, card |
| `payment`  | the API controller, `PaymentService`, the payments table, fingerprints, the checker, the 3-D Secure return | common, card, merchant, provider, webhook, api |

`api` is the interface and DTOs generated from
[payment-gateway.yaml](gateway-service/src/main/resources/static/openapi/payment-gateway.yaml), the webhook body
included. `LayeringTest` checks the table on every build, and also that `card` imports no Spring or Jakarta classes
and that no module imports a provider's package: Spring wires the connector in, so a new provider touches nothing
else. `webhook` knows nothing of payments: it stores and sends whatever JSON body `payment` gives it.

## A payment, step by step

1. `ApiKeyFilter` hashes `X-Api-Key` (SHA-256) and looks the merchant up; no match is 401. Paths are public only by
   an allow list (health, docs, `/3ds/`), so a new endpoint is protected by default.
2. Bean validation from the contract checks the request's shape; `PaymentMapper` then parses the card and the
   return URL: a number that fails the Luhn check is 422 `CARD_INVALID`, a return URL that is no URL 422
   `RETURN_URL_INVALID`.
3. `PaymentService` computes the request fingerprint. A payment with this `Idempotency-Key` already there is
   returned as a replay if the fingerprints match, or refused with 422 `IDEMPOTENCY_KEY_REUSED`. An expired card is
   422 `CARD_EXPIRED`. Requests refused here store nothing, so the key can be used again.
4. The payment is inserted as `PROCESSING` with its first check due (`INSERT ... ON CONFLICT DO NOTHING`; losing a
   race with a parallel request turns into a replay of the winner's payment).
5. The provider is called outside any transaction, with the payment's id as the reference and the gateway's
   `/3ds/return/{id}` as the return URL. What came back decides the status:

   | What happened                                          | Outcome          | Payment                                  |
   |--------------------------------------------------------|------------------|------------------------------------------|
   | approved                                               | APPROVED         | `SUCCEEDED`                              |
   | declined                                               | DECLINED         | `DECLINED` with the provider's code      |
   | requires_action with a challenge URL                   | REQUIRES_ACTION  | `REQUIRES_ACTION` with `nextAction`      |
   | 4xx: the request itself was refused                    | FAILED           | `FAILED`, `provider_rejected_request`    |
   | connection refused, unknown host, connect timeout      | FAILED           | `FAILED`, `provider_unavailable`         |
   | 5xx, read timeout, dropped connection, unreadable body | UNKNOWN          | stays `PROCESSING`                       |

6. The change is written with `UPDATE ... WHERE status IN ('PROCESSING', 'REQUIRES_ACTION')` (only `PROCESSING` for
   the move to `REQUIRES_ACTION`), in one transaction with its webhook event. Whichever of the request, the checker
   and the customer's return writes a change first, that one stands, and only it gets an event.

`PendingPaymentChecker` runs every `check-interval`. It claims the payments whose next check is due with
`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)`, which also moves their next check forward
(`check-interval` for `PROCESSING`, `challenge-check-interval` for `REQUIRES_ACTION`), so instances don't wait for
each other and skip what another one has just claimed. It looks each one up by reference: an outcome is recorded
as above; no record of the charge after `give-up-after` (10 minutes) is `FAILED`, `provider_no_record`; a failed
lookup changes nothing, since it says nothing about the charge, and the payment is asked about again.

Only states that can't be wrong are written: a payment is never marked `FAILED` while the card may have been
charged, and a challenge is never given up by the gateway while the bank may still approve it.

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

1. A new package under `provider`, with a `ProviderConnector` and its properties record.
2. Map the provider's answers to `ProviderResult`. `FAILED` only when the card was certainly not charged; anything
   in doubt is `UNKNOWN`, and the checker settles it with `lookup`. A 3-D Secure challenge is `REQUIRES_ACTION` with
   the URL to send the customer to; the provider must send the browser back to the `returnUrl` of the
   `ChargeRequest`.
3. Test the failure modes with WireMock, as `AcquirerSimConnectorTest` does.

With one provider, `PaymentService` takes the only `ProviderConnector` bean; choosing between several comes with
the second one.

## Decisions

- **JdbcClient and SQL, not JPA.** A handful of tables, and statements whose exact shape matters:
  `ON CONFLICT DO NOTHING`, `FOR UPDATE SKIP LOCKED`, updates guarded by the current status.
- **The payment is stored before the provider call.** An HTTP call can't be part of a database transaction; storing
  `PROCESSING` first means a crash mid-call leaves a payment for the checker, never a charge the gateway doesn't know
  about.
- **An outbox, not a webhook call in the request.** Sending from the request would either hold a transaction open
  over an HTTP call or lose the event on a crash between commit and send.
- **Standard Webhooks** for signing, rather than a scheme of the gateway's own: merchants can check signatures
  with existing libraries, and the scheme covers the id and the time, so a replayed webhook is caught.
- **Webhook secrets are stored as they are.** Signing needs the secret itself, so a hash won't do; in production it
  would be encrypted at rest with a key from a secrets manager.
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
| `gateway.payments.first-check-after`            | 30s                     | the first lookup of a payment left `PROCESSING`            |
| `gateway.payments.check-interval`               | 10s                     | how often the checker runs                                 |
| `gateway.payments.challenge-check-interval`     | 1m                      | how often a payment waiting for its challenge is looked up |
| `gateway.payments.give-up-after`                | 10m                     | when a charge the provider has no record of fails          |
| `gateway.providers.acquirer-sim.read-timeout`   | 5s                      | longer than the connect timeout, by a startup check        |
| `gateway.webhooks.dispatch-interval`            | 1s                      | how often the outbox is looked at                          |
| `gateway.webhooks.read-timeout`                 | 15s                     | how long a merchant's endpoint has to answer               |
| `gateway.webhooks.retry-delays`                 | 5s, 5m, 30m, 2h, 5h, 10h | the waits before each retry; then the event is given up   |
| `SIM_PUBLIC_URL`, `sim.challenge-ttl`           | localhost:8091, 15m     | acquirer-sim as browsers reach it; how long a challenge lasts |
| `MERCHANT_SIM_WEBHOOK_SECRET`                   | none: required          | merchant-sim's copy of the demo merchant's webhook secret  |
| `MERCHANT_SIM_FAIL_FIRST`                       | 0                       | deliveries of each webhook merchant-sim answers 503 first  |

Every settings record checks its values at startup and names the setting that is wrong.
