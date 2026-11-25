# payment-gateway

[![pipeline](https://gitlab.com/lecorrr/payment-gateway/badges/main/pipeline.svg)](https://gitlab.com/lecorrr/payment-gateway/-/pipelines?ref=main)
[![gateway-service coverage](https://gitlab.com/lecorrr/payment-gateway/badges/main/coverage.svg?job=verify&key_text=gateway+coverage&key_width=110)](https://gitlab.com/lecorrr/payment-gateway/-/pipelines?ref=main)

A card payment gateway for merchants. A merchant sends a card payment to one REST API; the gateway charges it with
a payment provider, records the outcome, tells the merchant by a signed webhook, and never stores the card number.
The provider in this version is **acquirer-sim**, a simulated card acquirer, and **merchant-sim** plays the shop.
Both live in this repository, so everything runs locally.

What it does:

- **Charges once per Idempotency-Key.** Repeating a request returns the payment the first one made; the same key
  with a different request is refused. Requests are told apart by a keyed hash (HMAC-SHA256), not by stored card
  numbers.
- **3-D Secure.** When the card's bank wants the customer to confirm, the payment is `REQUIRES_ACTION` with a link
  for the customer's browser. The browser comes back to the gateway, which asks the provider how the challenge went
  (the browser itself is not trusted) and sends the customer on to the shop.
- **Signed webhooks, from an outbox.** Every status a payment reaches is written in the same transaction as an
  event, and a dispatcher sends the events to the merchant, signed by the Standard Webhooks scheme. Failed
  deliveries are retried with growing delays for about 18 hours; delivery is at least once.
- **Handles lost answers.** If the provider doesn't answer in time, the payment stays `PROCESSING` and a background
  checker looks the charge up by its reference until it knows the outcome. The same checker settles 3-D Secure
  challenges left unfinished. Several instances can run it side by side (`FOR UPDATE SKIP LOCKED`).
- **Tells "not charged" from "don't know".** A refused request or a connection that never opened fails the payment;
  a 5xx, a read timeout or a dropped connection leaves it `PROCESSING` for the checker.
- **Keeps card data out.** Luhn and expiry checks up front; the card number, CVC and holder are never stored,
  logged or sent in a webhook, and tests check the database rows, the log output and the webhook bodies for them.
- **Contract first.** The API and the webhook body are written in OpenAPI and their Java types are generated from
  it; errors are RFC 9457 problem details with a `code`.
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
   printf 'GATEWAY_DEMO_MERCHANT_API_KEY=%s\nGATEWAY_FINGERPRINT_KEY=%s\nACQUIRER_SIM_API_KEY=%s\nGATEWAY_DEMO_MERCHANT_WEBHOOK_SECRET=whsec_%s\n' "$(openssl rand -hex 16)" "$(openssl rand -hex 32)" "$(openssl rand -hex 16)" "$(openssl rand -base64 32)" > .env
   ```

   The demo uses ports 8090 (gateway), 8091 (acquirer-sim), 8092 (merchant-sim) and 5436 (PostgreSQL) on
   localhost. If one is taken, pick others in `.env` and use them in the URLs below, for example:

   ```bash
   printf 'GATEWAY_PORT=8190\nACQUIRER_SIM_PORT=8191\nMERCHANT_SIM_PORT=8192\nGATEWAY_DB_PORT=5446\n' >> .env
   ```

3. Start the gateway, acquirer-sim, merchant-sim and PostgreSQL:

   ```bash
   docker compose up -d --build
   ```

4. Pay with a test card, using the demo merchant's key:

   ```bash
   KEY=$(grep '^GATEWAY_DEMO_MERCHANT_API_KEY=' .env | cut -d= -f2)
   ```

   ```bash
   curl -s -w '\n' -X POST http://localhost:8090/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-1001-1" -H "Content-Type: application/json" -d '{"orderId": "ORDER-1001", "amount": 15000, "currency": "UAH", "description": "Two tickets", "card": {"number": "4111 1111 1111 1111", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}'
   ```

   Send the same command again: same payment, HTTP 200 and `Idempotent-Replayed: true`. Change the amount and keep
   the key: 422 `IDEMPOTENCY_KEY_REUSED`.

5. Read a payment back by the `id` from the answer, for example to watch the slow test card go from `PROCESSING`
   to `SUCCEEDED`:

   ```bash
   curl -s -w '\n' http://localhost:8090/api/v1/payments/PAYMENT_ID -H "X-Api-Key: $KEY"
   ```

6. Pay with 3-D Secure, sending the customer back to merchant-sim's page afterwards:

   ```bash
   curl -s -w '\n' -X POST http://localhost:8090/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-1002-1" -H "Content-Type: application/json" -d '{"orderId": "ORDER-1002", "amount": 15000, "currency": "UAH", "returnUrl": "http://localhost:8092/return", "card": {"number": "4000 0000 0000 3220", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}'
   ```

   The answer is `REQUIRES_ACTION`. Open its `nextAction.url` in a browser and press **Confirm** (or **Fail the
   challenge**). The browser ends on merchant-sim's page, which shows the status the webhook brought;
   http://localhost:8092/events lists every webhook merchant-sim received.

The API docs are at http://localhost:8090/swagger-ui.html (use **Authorize** with the key), acquirer-sim's at
http://localhost:8091/swagger-ui.html. Stop everything with `docker compose down`.

### Test cards

| Card number         | What happens                                                                              |
|---------------------|-------------------------------------------------------------------------------------------|
| 4111 1111 1111 1111 | `SUCCEEDED` (Visa)                                                                        |
| 5555 5555 5555 4444 | `SUCCEEDED` (Mastercard)                                                                  |
| 4000 0000 0000 0002 | `DECLINED`, `do_not_honor`                                                                |
| 4000 0000 0000 9995 | `DECLINED`, `insufficient_funds`                                                          |
| 4000 0000 0000 0119 | `DECLINED`, `processing_error`                                                            |
| 4000 0000 0000 0259 | The answer comes too late: `PROCESSING`, then `SUCCEEDED` about 30 s later by lookup      |
| 4000 0000 0000 3220 | `REQUIRES_ACTION`; passing the challenge `SUCCEEDED`, failing it `authentication_failed`  |

CVC `000` is declined with `incorrect_cvc`. A challenge left open for 15 minutes is declined with
`authentication_expired`. A past expiry date or a number that fails the Luhn check is refused by the gateway itself
(422 `CARD_EXPIRED`, `CARD_INVALID`) before any provider sees it.

## API

| Request                         | Answer                                                                          |
|---------------------------------|---------------------------------------------------------------------------------|
| `POST /api/v1/payments`         | 201 with `Location`; 200 and `Idempotent-Replayed: true` for a repeated request |
| `GET /api/v1/payments/{id}`     | 200, or 404 for an unknown id or another merchant's payment                     |
| `GET /3ds/return/{id}`          | for the customer's browser after 3-D Secure: 303 to the payment's `returnUrl`   |

The first two need `X-Api-Key`; the POST also needs `Idempotency-Key`. A payment is `PROCESSING`, or
`REQUIRES_ACTION` with a `nextAction` while the customer is at the challenge, until it is `SUCCEEDED`, `DECLINED` or
`FAILED`, with the reason in `declineCode`. Errors look like this:

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

## Webhooks

A merchant with a webhook endpoint gets a POST for every status a payment reaches after `PROCESSING`. The body is a
`PaymentEvent`: `type` (`payment.requires_action`, `payment.succeeded`, `payment.declined`, `payment.failed`),
`timestamp` and the payment as the API shows it in `data`. Three headers sign it:

| Header              | Value                                                                                   |
|---------------------|-----------------------------------------------------------------------------------------|
| `webhook-id`        | the event's id, the same on every retry: deduplicate by it                              |
| `webhook-timestamp` | when this attempt was sent, in Unix seconds: refuse old ones                            |
| `webhook-signature` | `v1,` and the Base64 HMAC-SHA256 of `{webhook-id}.{webhook-timestamp}.{body}`           |

The HMAC key is the endpoint's `whsec_` secret without the prefix, Base64-decoded. Check the signature on the raw
body before parsing it; [WebhookVerifier](merchant-sim/src/main/java/dev/tkachuk/merchant/WebhookVerifier.java)
in merchant-sim shows how. Any 2xx answer counts as delivered. Anything else, a redirect included, is retried after
5 s, 5 min, 30 min, 2 h, 5 h and 10 h, each with up to a tenth more at random, then given up; 410 Gone is given up
at once. Webhooks can come twice and out of order, so go by the payment's `status` and `updatedAt`.

## Layout

```
gateway-service/   the merchant API, 3-D Secure return, webhooks
acquirer-sim/      the simulated card acquirer with its challenge pages, a separate app with its own API
merchant-sim/      the simulated shop: takes and checks webhooks, shows the page the customer comes back to
ci/                scripts for the pipeline
docs/              setting up GitLab, the runner and the GitHub mirror
```

How the parts fit, and why: [ARCHITECTURE.md](ARCHITECTURE.md).

## CI

The repository lives on [GitLab](https://gitlab.com/lecorrr/payment-gateway), where every push runs the tests, the
coverage gate and the image builds on a self-hosted runner ([docs/gitlab-setup.md](docs/gitlab-setup.md)). The
GitHub copy is a mirror of it. The coverage badge shows gateway-service's line coverage; the pipeline log has the
other modules' too.

## Next

A monobank acquiring connector in test mode; routing between providers with failover; refunds; a Helm chart for
several environments.
