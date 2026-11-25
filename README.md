# payment-gateway

A card payment gateway for merchants. A merchant sends a card payment to one REST API; the gateway charges it with
a payment provider, records the outcome, and never stores the card number. The provider in this version is
**acquirer-sim**, a simulated card acquirer that lives in this repository, so everything runs locally.

What it does:

- **Charges once per Idempotency-Key.** Repeating a request returns the payment the first one made; the same key
  with a different request is refused. Requests are told apart by a keyed hash (HMAC-SHA256), not by stored card
  numbers.
- **Handles lost answers.** If the provider doesn't answer in time, the payment stays `PROCESSING` and a background
  checker looks the charge up by its reference until it knows the outcome. Several instances can run the checker
  side by side (`FOR UPDATE SKIP LOCKED`).
- **Tells "not charged" from "don't know".** A refused request or a connection that never opened fails the payment;
  a 5xx, a read timeout or a dropped connection leaves it `PROCESSING` for the checker.
- **Keeps card data out.** Luhn and expiry checks up front; the card number, CVC and holder are never stored or
  logged, and tests check the database rows and the log output for them.
- **Contract first.** The API is written in OpenAPI and its Spring interfaces are generated from it; errors are
  RFC 9457 problem details with a `code`.
- **Merchants are isolated.** API keys are stored as SHA-256 hashes; a merchant sees only its own payments.

## Stack

Java 21, Spring Boot 4.1 (Web MVC, JdbcClient, Flyway, Actuator, RestClient), PostgreSQL 17, OpenAPI Generator,
springdoc, JUnit 5, WireMock, Testcontainers, JaCoCo, Docker, GitLab CI.

## Run it

Needs Java 21 and Docker.

1. Build and test. This runs the unit tests, the integration tests against PostgreSQL in Testcontainers, and the
   coverage gate:

   ```bash
   ./mvnw verify
   ```

2. Make `.env` with random keys (it stays out of git):

   ```bash
   printf 'GATEWAY_DEMO_MERCHANT_API_KEY=%s\nGATEWAY_FINGERPRINT_KEY=%s\nACQUIRER_SIM_API_KEY=%s\n' "$(openssl rand -hex 16)" "$(openssl rand -hex 32)" "$(openssl rand -hex 16)" > .env
   ```

   The demo uses ports 8090 (gateway), 8091 (acquirer-sim) and 5436 (PostgreSQL) on localhost. If one is taken,
   pick others in `.env` and use them in the URLs below, for example:

   ```bash
   printf 'GATEWAY_PORT=8190\nACQUIRER_SIM_PORT=8191\nGATEWAY_DB_PORT=5446\n' >> .env
   ```

3. Start the gateway, acquirer-sim and PostgreSQL:

   ```bash
   docker compose up -d --build
   ```

4. Pay with a test card, using the demo merchant's key:

   ```bash
   KEY=$(grep '^GATEWAY_DEMO_MERCHANT_API_KEY=' .env | cut -d= -f2)
   ```

   ```bash
   curl -s -X POST http://localhost:8090/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-1001-1" -H "Content-Type: application/json" -d '{"orderId": "ORDER-1001", "amount": 15000, "currency": "UAH", "description": "Two tickets", "card": {"number": "4111 1111 1111 1111", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}'
   ```

   Send the same command again: same payment, HTTP 200 and `Idempotent-Replayed: true`. Change the amount and keep
   the key: 422 `IDEMPOTENCY_KEY_REUSED`.

The API docs are at http://localhost:8090/swagger-ui.html (use **Authorize** with the key), acquirer-sim's at
http://localhost:8091/swagger-ui.html. Stop everything with `docker compose down`.

### Test cards

| Card number         | What happens                                                                          |
|---------------------|---------------------------------------------------------------------------------------|
| 4111 1111 1111 1111 | `SUCCEEDED` (Visa)                                                                    |
| 5555 5555 5555 4444 | `SUCCEEDED` (Mastercard)                                                              |
| 4000 0000 0000 0002 | `DECLINED`, `do_not_honor`                                                            |
| 4000 0000 0000 9995 | `DECLINED`, `insufficient_funds`                                                      |
| 4000 0000 0000 0119 | `DECLINED`, `processing_error`                                                        |
| 4000 0000 0000 0259 | The answer comes too late: `PROCESSING`, then `SUCCEEDED` about 30 s later by lookup  |

CVC `000` is declined with `incorrect_cvc`. A past expiry date or a number that fails the Luhn check is refused by
the gateway itself (422 `CARD_EXPIRED`, `CARD_INVALID`) before any provider sees it.

## API

| Request                         | Answer                                                                          |
|---------------------------------|---------------------------------------------------------------------------------|
| `POST /api/v1/payments`         | 201 with `Location`; 200 and `Idempotent-Replayed: true` for a repeated request |
| `GET /api/v1/payments/{id}`     | 200, or 404 for an unknown id or another merchant's payment                     |

Both need `X-Api-Key`; the POST also needs `Idempotency-Key`. A payment is `PROCESSING` until it is `SUCCEEDED`,
`DECLINED` or `FAILED`, with the reason in `declineCode`. Errors look like this:

```json
{
  "type": "urn:payment-gateway:error:idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 422,
  "detail": "This Idempotency-Key was sent with a different payment request; use a new key for a new payment",
  "code": "IDEMPOTENCY_KEY_REUSED"
}
```

The contract: [payment-gateway.yaml](gateway-service/src/main/resources/static/openapi/payment-gateway.yaml).

## Layout

```
gateway-service/   the merchant API
acquirer-sim/      the simulated card acquirer, a separate app with its own API
ci/                scripts for the pipeline
docs/              setting up GitLab, the runner and the GitHub mirror
```

How the parts fit, and why: [ARCHITECTURE.md](ARCHITECTURE.md).

## CI

The repository lives on GitLab, where every push runs the tests, the coverage gate and the image builds on a
self-hosted runner ([docs/gitlab-setup.md](docs/gitlab-setup.md)); the GitHub copy is a mirror.

## Next

3-D Secure and signed webhooks to merchants; a monobank acquiring connector in test mode; routing between
providers with failover; refunds; a Helm chart for several environments.
