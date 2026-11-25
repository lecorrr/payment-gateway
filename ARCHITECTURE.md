# Architecture

Two Spring Boot apps in one Maven build. **gateway-service** is the merchant API and owns the payments;
**acquirer-sim** plays a card acquirer behind it, with its own API, error format and naming (snake_case), the way a
real provider would. The two share no code.

## Modules of gateway-service

| Module     | Owns                                                                                    | May use                        |
|------------|-----------------------------------------------------------------------------------------|--------------------------------|
| `common`   | currencies, `ErrorCode` and `GatewayException`, problem details, the clock              | nothing                        |
| `card`     | card number (Luhn, brand, masking), expiry, CVC; no framework imports                   | common                         |
| `merchant` | merchants, the API key filter, the current merchant, the demo merchant                  | common                         |
| `provider` | the connector contract (`ProviderConnector`, `ChargeRequest`, `ProviderResult`); one package per provider below it | common, card |
| `payment`  | the API controller, `PaymentService`, the payments table, fingerprints, the checker     | common, card, merchant, provider, api |

`api` is the interface and DTOs generated from
[payment-gateway.yaml](gateway-service/src/main/resources/static/openapi/payment-gateway.yaml). `LayeringTest`
checks the table on every build, and also that `card` imports no Spring or Jakarta classes and that no module
imports a provider's package: Spring wires the connector in, so a new provider touches nothing else.

## A payment, step by step

1. `ApiKeyFilter` hashes `X-Api-Key` (SHA-256) and looks the merchant up; no match is 401. Paths are public only by
   an allow list (health, docs), so a new endpoint is protected by default.
2. Bean validation from the contract checks the request's shape; `PaymentMapper` then parses the card, and a number
   that fails the Luhn check is 422 `CARD_INVALID`.
3. `PaymentService` computes the request fingerprint. A payment with this `Idempotency-Key` already there is
   returned as a replay if the fingerprints match, or refused with 422 `IDEMPOTENCY_KEY_REUSED`. An expired card is
   422 `CARD_EXPIRED`. Requests refused here store nothing, so the key can be used again.
4. The payment is inserted as `PROCESSING` with its first check due (`INSERT ... ON CONFLICT DO NOTHING`; losing a
   race with a parallel request turns into a replay of the winner's payment).
5. The provider is called outside any transaction, with the payment's id as the reference. What came back decides
   the status:

   | What happened                                          | Outcome   | Payment                                |
   |--------------------------------------------------------|-----------|----------------------------------------|
   | approved                                               | APPROVED  | `SUCCEEDED`                            |
   | declined                                               | DECLINED  | `DECLINED` with the provider's code    |
   | 4xx: the request itself was refused                    | FAILED    | `FAILED`, `provider_rejected_request`  |
   | connection refused, unknown host, connect timeout      | FAILED    | `FAILED`, `provider_unavailable`       |
   | 5xx, read timeout, dropped connection, unreadable body | UNKNOWN   | stays `PROCESSING`                     |

6. The outcome is written with `UPDATE ... WHERE status = 'PROCESSING'`, so whichever of the request and the checker
   records an outcome first, that one stands.

`PendingPaymentChecker` runs every `check-interval`. It claims the `PROCESSING` payments that are due with
`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)`, which also moves their next check forward, so instances
don't wait for each other and skip what another one has just claimed. It looks each one up by reference: an outcome is
recorded as above; no record of the charge after `give-up-after` (10 minutes) is `FAILED`, `provider_no_record`; a
failed lookup changes nothing, since it says nothing about the charge, and the payment is asked about again.

Only states that can't be wrong are written: a payment is never marked `FAILED` while the card may have been
charged.

## Card data

- **Stored:** the brand, the first six and the last four digits, which PCI DSS allows to be shown.
- **Never stored:** the number, the CVC and the holder's name. The fingerprint is an HMAC of the whole request
  except the CVC, with a secret key (PCI DSS allows keyed hashes of the full card number); the CVC must not be kept
  in any form.
- **Never logged:** every type that holds card data masks itself in `toString()`, validation errors name the field
  and the rule but not the value, and Jackson's parse errors are not passed on. The generated request DTO can't
  mask itself, so it is never logged, and `application.yml` keeps Spring's request body logging off.
  `CreatePaymentIT` checks the database rows and the log output for the card number and the holder.

## Adding a provider

1. A new package under `provider`, with a `ProviderConnector` and its properties record.
2. Map the provider's answers to `ProviderResult`. `FAILED` only when the card was certainly not charged; anything
   in doubt is `UNKNOWN`, and the checker settles it with `lookup`.
3. Test the failure modes with WireMock, as `AcquirerSimConnectorTest` does.

With one provider, `PaymentService` takes the only `ProviderConnector` bean; choosing between several comes with
the second one.

## Decisions

- **JdbcClient and SQL, not JPA.** Two tables, and statements whose exact shape matters: `ON CONFLICT DO NOTHING`,
  `FOR UPDATE SKIP LOCKED`, updates guarded by the current status.
- **The payment is stored before the provider call.** An HTTP call can't be part of a database transaction; storing
  `PROCESSING` first means a crash mid-call leaves a payment for the checker, never a charge the gateway doesn't know
  about.
- **422 for a reused Idempotency-Key**, as the IETF Idempotency-Key draft says: retrying won't help, unlike a 409.
- **One clock.** All timestamps come from the `Clock` bean, truncated to microseconds like PostgreSQL's, so a
  payment returned right away equals the one read back later.

## Configuration

| Setting                                       | Default                 | What it is                                          |
|-----------------------------------------------|-------------------------|-----------------------------------------------------|
| `GATEWAY_FINGERPRINT_KEY`                     | none: required          | secret of the request fingerprints, 32+ characters  |
| `GATEWAY_DEMO_MERCHANT_API_KEY`               | empty: no demo merchant | the demo merchant's API key, 24+ characters         |
| `ACQUIRER_SIM_URL`, `ACQUIRER_SIM_API_KEY`    | localhost:8091, none    | where acquirer-sim is, and its key                  |
| `gateway.payments.first-check-after`          | 30s                     | the first lookup of a payment left `PROCESSING`     |
| `gateway.payments.check-interval`             | 10s                     | how often the checker runs                          |
| `gateway.payments.give-up-after`              | 10m                     | when a charge the provider has no record of fails   |
| `gateway.providers.acquirer-sim.read-timeout` | 5s                      | longer than the connect timeout, by a startup check |

Every settings record checks its values at startup and names the setting that is wrong.
