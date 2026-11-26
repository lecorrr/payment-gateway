# payment-gateway

[![pipeline](https://gitlab.com/lecorrr/payment-gateway/badges/main/pipeline.svg)](https://gitlab.com/lecorrr/payment-gateway/-/pipelines?ref=main)
[![gateway-service coverage](https://gitlab.com/lecorrr/payment-gateway/badges/main/coverage.svg?job=verify&key_text=gateway+coverage&key_width=110)](https://gitlab.com/lecorrr/payment-gateway/-/pipelines?ref=main)

A card payment gateway for merchants. A merchant sends a card payment to one REST API; the gateway asks
**risk-service** over gRPC whether the payment may go ahead, charges it with a payment provider, records the outcome,
tells the merchant by a signed webhook, and never stores the card number. A payment can be refunded, in full or in
parts. It has two providers, **acquirer-sim**, a simulated card acquirer, and **monobank**'s acquiring API, and moves
new payments to the other one when a provider is down. risk-service, acquirer-sim, which can also play monobank, and
**merchant-sim**, which plays the shop, live in this repository, so everything runs locally, watched by Prometheus,
Grafana and Jaeger. A Helm chart runs the same apps on Kubernetes, with values for dev, staging and production; dev
and staging run on a local k3d cluster.

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
- **Refunds, in full or in parts.** A `SUCCEEDED` payment can be refunded as often as its amount allows, once per
  Idempotency-Key, through the provider that charged it. Each refund is stored before the provider is asked, under a
  lock on the payment, so refunds sent side by side never give back more than was charged. One whose answer got
  lost is looked up as a payment is.
- **Risk check over gRPC.** Before any provider sees a payment, risk-service checks it: a blocked card range, an
  amount over its currency's limit, too many payments with one card lately (counted in Redis, once per
  Idempotency-Key). It gets a keyed hash of the card number, never the number. When risk-service is slow (a 300 ms
  deadline) or down, payments go ahead by default, and a circuit breaker stops them waiting for it.
- **One API, several providers.** Each provider sits behind a connector that turns its API, its statuses and its
  error codes into the gateway's, so a merchant sees the same decline codes whichever provider charged the card.
  Every payment is looked up with the provider that charged it.
- **Failover.** New payments go to the providers in a set order, and on to the next one only when a provider never
  got the charge, so no card is charged twice. A circuit breaker per provider (Resilience4j) skips one that keeps
  failing, and Redis shares that between gateway instances.
- **Keeps card data out.** Luhn and expiry checks up front; the card number, CVC and holder are never stored,
  logged or sent in a webhook, and tests check the database rows, the log output and the webhook bodies for them.
- **Contract first.** The API and the webhook body are written in OpenAPI and their Java types are generated from
  it; errors are RFC 9457 problem details with a `code`.
- **Merchants are isolated.** API keys are stored as SHA-256 hashes; a merchant sees only its own payments.
- **Metrics and traces.** Every service has Prometheus metrics: payments and refunds by outcome and provider,
  risk-service's answers, webhook deliveries, the circuit breakers, latencies. A Grafana dashboard shows them.
  OpenTelemetry traces follow a payment from the gateway through risk-service to the provider, and a webhook to the
  merchant, into Jaeger; every log line carries its trace id.
- **Kubernetes, per environment.** One Helm chart, with values for dev, staging and production. Staging runs two
  gateways and two risk-services, the gateways autoscaled, and a rollout loses no payment; production takes managed
  PostgreSQL and Redis and the real monobank. Each pod reaches only what it needs (network policies), meets the
  restricted Pod Security Standard and is probed on a management port the Ingress doesn't route.
- **Quality gates in the pipeline.** Every push is built and tested, with 80 % line coverage per module, SpotBugs
  and FindSecBugs over the classes, the chart checked for every environment against Kubernetes' schemas, the whole
  history searched for secrets (gitleaks) and the images scanned for known vulnerabilities (Trivy). On main, the
  images then go to the project's registry, run on a k3d cluster inside the pipeline for the end-to-end check, and
  only then get their version's tag: what is released is exactly what was checked.

## Stack

Java 21, Spring Boot 4.1 (Web MVC, JdbcClient, Flyway, Actuator, RestClient, Data Redis, gRPC), Protocol Buffers,
PostgreSQL 17, Redis 8, Resilience4j, Micrometer, OpenTelemetry, Prometheus, Grafana, Jaeger, OpenAPI Generator,
springdoc, JUnit 6, WireMock, Testcontainers, JaCoCo, SpotBugs, FindSecBugs, Docker, Kubernetes, Helm, k3d,
kubeconform, gitleaks, Trivy, GitLab CI and its container registry.

## Run it

Needs Java 21 and Docker.

1. Build and test. This runs the unit tests, the integration tests against PostgreSQL and Redis in Testcontainers,
   and the coverage gate:

   ```bash
   ./mvnw verify
   ```

2. Make `.env` with random keys (it stays out of git):

   ```bash
   printf 'GATEWAY_DEMO_MERCHANT_API_KEY=%s\nGATEWAY_FINGERPRINT_KEY=%s\nACQUIRER_SIM_API_KEY=%s\nGATEWAY_DEMO_MERCHANT_WEBHOOK_SECRET=whsec_%s\n' "$(openssl rand -hex 16)" "$(openssl rand -hex 32)" "$(openssl rand -hex 16)" "$(openssl rand -base64 32)" > .env
   ```

   The demo uses ports 8090 (gateway), 8091 (acquirer-sim), 8093 (monobank-sim), 8092 (merchant-sim), 5436
   (PostgreSQL), 3000 (Grafana), 9090 (Prometheus) and 16686 (Jaeger) on localhost. If one is taken, pick others in
   `.env` and use them in the URLs below, for example:

   ```bash
   printf 'GATEWAY_PORT=8190\nACQUIRER_SIM_PORT=8191\nMONOBANK_SIM_PORT=8193\nMERCHANT_SIM_PORT=8192\nGATEWAY_DB_PORT=5446\nGRAFANA_PORT=8194\nPROMETHEUS_PORT=8195\nJAEGER_PORT=8196\n' >> .env
   ```

3. Start the gateway, risk-service, both simulated providers, merchant-sim, PostgreSQL, Redis, Prometheus, Grafana
   and Jaeger:

   ```bash
   docker compose up -d --build
   ```

   The images are built from the jars of step 1, so start them once step 1 has passed: a build that fails leaves
   the jars of the modules from there on as the build before made them, and compose would run those. Each app
   shows its version at `/actuator/info`, for example http://localhost:8090/actuator/info.

4. Pay with a test card, using the demo merchant's key:

   ```bash
   KEY=$(grep '^GATEWAY_DEMO_MERCHANT_API_KEY=' .env | cut -d= -f2)
   ```

   ```bash
   curl -s -w '\n' -X POST http://localhost:8090/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-1001-1" -H "Content-Type: application/json" -d '{"orderId": "ORDER-1001", "amount": 15000, "currency": "UAH", "description": "Two tickets", "card": {"number": "4111 1111 1111 1111", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}'
   ```

   The answer's `provider` is `monobank`: compose sends new payments to monobank-sim first. Send the same command
   again: same payment, HTTP 200 and `Idempotent-Replayed: true`. Change the amount and keep the key: 422
   `IDEMPOTENCY_KEY_REUSED`.

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

7. Refund part of the payment from step 4, by its `id`:

   ```bash
   curl -s -w '\n' -X POST http://localhost:8090/api/v1/payments/PAYMENT_ID/refunds -H "X-Api-Key: $KEY" -H "Idempotency-Key: refund-1001-1" -H "Content-Type: application/json" -d '{"amount": 5000}'
   ```

   The refund is `PROCESSING`: monobank may answer a refund with "processing" before it is done, and monobank-sim
   always does. About two seconds later its webhook comes, the gateway looks the refund up, and it is `SUCCEEDED`.
   The payment's `refundedAmount` is 5000 now, and http://localhost:8092/events has the `refund.succeeded` webhook.
   The payment's refunds:

   ```bash
   curl -s -w '\n' http://localhost:8090/api/v1/payments/PAYMENT_ID/refunds -H "X-Api-Key: $KEY"
   ```

   Ask for more than is left, 10001 or more here, and the answer is 409 `REFUND_AMOUNT_TOO_LARGE`; the same key
   with another amount gets 422 `IDEMPOTENCY_KEY_REUSED`. A payment acquirer-sim charged, as in the next step, is
   refunded by acquirer-sim, which decides at once.

8. Take monobank away, then pay as in step 4 with a new `Idempotency-Key`:

   ```bash
   docker compose stop monobank-sim
   ```

   The payment still succeeds, with `"provider": "acquirer-sim"`: monobank never got the charge, so the gateway
   gave it to the next provider. After a few such payments monobank's circuit opens, and new payments skip it for
   30 s at a time without trying it; `docker compose logs gateway` shows when. Meanwhile Redis holds the open
   circuit for other gateway instances to see:

   ```bash
   docker compose exec redis redis-cli keys 'payment-gateway:*'
   ```

   Start monobank-sim again, and once two trial charges get through, new payments go back to it:

   ```bash
   docker compose start monobank-sim
   ```

   It comes back with a new signing key, so for up to 10 minutes the gateway refuses its webhooks, as after a key
   change at monobank; the payments don't depend on them.

9. Pay with a card from the range risk-service blocks: `DECLINED` with `risk_declined`, and no provider saw it:

   ```bash
   curl -s -w '\n' -X POST http://localhost:8090/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-1003-1" -H "Content-Type: application/json" -d '{"orderId": "ORDER-1003", "amount": 15000, "currency": "UAH", "card": {"number": "4000 0900 0000 0003", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}'
   ```

   Then take risk-service away, and payments go ahead unchecked; `docker compose logs gateway` says so for each:

   ```bash
   docker compose stop risk-service
   ```

   `docker compose start risk-service` brings it back; within 30 s, once its circuit lets payments through again,
   they are checked again.

10. Look at what the steps did. Grafana, at http://localhost:3000, opens on the payment-gateway dashboard: payments
    and refunds by outcome and provider, latencies, the providers' circuits, risk-service's answers and the webhook
    deliveries. In Jaeger, at http://localhost:16686, pick gateway-service and **Find Traces**: a payment's trace
    shows the request, the risk check in risk-service and the charge at monobank-sim. Prometheus is at
    http://localhost:9090, its targets under **Status**. A log line shows its trace id in `[traceId-spanId]`, so one
    payment's lines can be found in both services:

    ```bash
    docker compose logs gateway risk-service | grep TRACE_ID
    ```

The API docs are at http://localhost:8090/swagger-ui.html (use **Authorize** with the key), acquirer-sim's, the
monobank-style part included, at http://localhost:8091/swagger-ui.html. Stop everything with `docker compose down`.

### Test cards

| Card number         | What happens                                                                              |
|---------------------|-------------------------------------------------------------------------------------------|
| 4111 1111 1111 1111 | `SUCCEEDED` (Visa)                                                                        |
| 5555 5555 5555 4444 | `SUCCEEDED` (Mastercard)                                                                  |
| 4000 0000 0000 0002 | `DECLINED`, `do_not_honor`                                                                |
| 4000 0000 0000 9995 | `DECLINED`, `insufficient_funds`                                                          |
| 4000 0000 0000 0119 | `DECLINED`, `processing_error`                                                            |
| 4000 0000 0000 0259 | The answer comes too late: `PROCESSING`, then `SUCCEEDED` about 30 s later by lookup      |
|                     | (so do its refunds through acquirer-sim)                                                  |
| 4000 0000 0000 3220 | `REQUIRES_ACTION`; passing the challenge `SUCCEEDED`, failing it `authentication_failed`  |
| 4000 0000 0000 5126 | `SUCCEEDED`; every refund of it `FAILED`, `refund_declined`                               |
| 4000 0900 0000 0003 | `DECLINED`, `risk_declined`: risk-service blocks the card range 400009                    |

risk-service also declines, with `risk_declined`, a payment over 100 000.00 UAH (3 000.00 USD or EUR), and a card's
eleventh payment within ten minutes; retries with the same Idempotency-Key count once. CVC `000` is declined with
`incorrect_cvc`. A challenge left open for 15 minutes is declined with
`authentication_expired`. A past expiry date or a number that fails the Luhn check is refused by the gateway itself
(422 `CARD_EXPIRED`, `CARD_INVALID`) before any provider sees it.

## Run it on Kubernetes

The chart in [deploy/helm/payment-gateway](deploy/helm/payment-gateway) puts one environment into a namespace. dev
and staging run on a local [k3d](https://k3d.io) cluster, which is k3s in Docker, with one server and two agents;
production is an example the pipeline checks and nothing installs:

|                   | dev                         | staging                            | production (example)                |
|-------------------|-----------------------------|------------------------------------|-------------------------------------|
| gateway           | 1 pod                       | 2 to 4 by CPU, a disruption budget | 3 to 10 by CPU, a disruption budget |
| risk-service      | 1 pod                       | 2, a disruption budget             | 3 to 6 by CPU, a disruption budget  |
| providers         | monobank-sim, acquirer-sim  | monobank-sim, acquirer-sim         | monobank                            |
| PostgreSQL, Redis | in the namespace            | in the namespace                   | managed: Amazon RDS, ElastiCache    |
| reached at        | http://*.dev.localhost:8280 | http://*.staging.localhost:8280    | https://pay.example.com             |

It needs Docker, [k3d](https://k3d.io/stable/#installation) 5.9, [kubectl](https://kubernetes.io/docs/tasks/tools/)
1.34 to 1.36 and [Helm](https://helm.sh/docs/intro/install/) 4, and port 8280 free on localhost. Names under
`.localhost` lead to this machine in browsers and in curl.

1. Build and test, as for compose:

   ```bash
   ./mvnw clean verify
   ```

2. Create the cluster:

   ```bash
   deploy/k3d/create-cluster.sh
   ```

3. Put dev on it. This builds the images from the jars and loads them into the nodes, makes the namespace
   `payment-gateway-dev` with a Secret of new random keys, installs the chart with `values-dev.yaml`, waits until
   every app is ready and runs `helm test`:

   ```bash
   deploy/k3d/deploy.sh dev
   ```

4. Check it end to end. Through the Ingress, as a merchant and a customer's browser would, it pays, replays,
   refunds and goes through 3-D Secure, and from inside the pods it checks the network policies:

   ```bash
   deploy/k3d/smoke-test.sh dev
   ```

5. Pay by hand with the demo merchant's key, which is in the Secret:

   ```bash
   KEY=$(kubectl --context k3d-payment-gateway -n payment-gateway-dev get secret payment-gateway -o jsonpath='{.data.GATEWAY_DEMO_MERCHANT_API_KEY}' | base64 -d)
   ```

   ```bash
   curl -s -w '\n' -X POST http://gateway.dev.localhost:8280/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-2001-1" -H "Content-Type: application/json" -d '{"orderId": "ORDER-2001", "amount": 15000, "currency": "UAH", "returnUrl": "http://shop.dev.localhost:8280/return", "card": {"number": "4000 0000 0000 3220", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}'
   ```

   The answer's `nextAction.url` is monobank-sim's challenge page at http://monobank.dev.localhost:8280; confirm
   it, and the browser ends on the shop's page. The API docs are at http://gateway.dev.localhost:8280/swagger-ui.html.

6. Staging goes the same way, with `deploy/k3d/deploy.sh staging` and `deploy/k3d/smoke-test.sh staging`. There the
   smoke test also checks that both risk-service pods get calls, and rolls the gateways over while it pays: no
   payment may be lost.

After a new build, `deploy.sh` again puts it out: images of other jars get a new tag, and the pods are replaced
one by one. It can also put out the images the pipeline released, from the project's registry on GitLab, which
anyone may pull from, with nothing built here:

```bash
IMAGE_REGISTRY=registry.gitlab.com/lecorrr/payment-gateway IMAGE_TAG=0.8.0 deploy/k3d/deploy.sh staging
```

`IMAGE_TAG` can also be a commit's full SHA: the images the pipeline pushed for that commit. An environment goes,
with its database and keys, like this:

```bash
helm --kube-context k3d-payment-gateway uninstall payment-gateway -n payment-gateway-dev
```

```bash
kubectl --context k3d-payment-gateway delete namespace payment-gateway-dev
```

and the cluster with `k3d cluster delete payment-gateway`. How the chart is built, and why:
[ARCHITECTURE.md](ARCHITECTURE.md#kubernetes).

## API

| Request                                   | Answer                                                                   |
|-------------------------------------------|--------------------------------------------------------------------------|
| `POST /api/v1/payments`                   | 201 with `Location`; 200 and `Idempotent-Replayed: true` for a repeat    |
| `GET /api/v1/payments/{id}`               | 200, or 404 for an unknown id or another merchant's payment              |
| `POST /api/v1/payments/{id}/refunds`      | 201 with `Location`; 200 and `Idempotent-Replayed: true` for a repeat    |
| `GET /api/v1/payments/{id}/refunds`       | 200, the payment's refunds, the newest first                             |
| `GET /api/v1/payments/{id}/refunds/{rid}` | 200, or 404                                                              |
| `GET /3ds/return/{id}`                    | for the customer's browser after 3-D Secure: 303 to its `returnUrl`      |
| `POST /providers/monobank/webhooks`       | for monobank, when an invoice changes: 200 once its signature checks out |

The payment and refund requests need `X-Api-Key`, and their POSTs an `Idempotency-Key`. A payment is `PROCESSING`,
or `REQUIRES_ACTION` with a `nextAction` while the customer is at the challenge, until it is `SUCCEEDED`, `DECLINED`
or `FAILED`, with the reason in `declineCode`. A refund is `PROCESSING` until it is `SUCCEEDED` or `FAILED`, with the
reason in `failureCode`. Only a `SUCCEEDED` payment can be refunded (409 `PAYMENT_NOT_REFUNDABLE` otherwise), and
only by what its earlier refunds left (409 `REFUND_AMOUNT_TOO_LARGE`); a `FAILED` refund leaves its amount free
again. The payment's `refundedAmount` adds up its `SUCCEEDED` refunds. Errors look like this:

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

A merchant with a webhook endpoint gets a POST for every status a payment reaches after `PROCESSING`, and for the
outcome of every refund. A payment's is a `PaymentEvent`: `type` (`payment.requires_action`, `payment.succeeded`,
`payment.declined`, `payment.failed`), `timestamp` and the payment as the API shows it in `data`. A refund's is a
`RefundEvent`, `refund.succeeded` or `refund.failed`, with the refund in `data`. Three headers sign each:

| Header              | Value                                                                                   |
|---------------------|-----------------------------------------------------------------------------------------|
| `webhook-id`        | the event's id, the same on every retry: deduplicate by it                              |
| `webhook-timestamp` | when this attempt was sent, in Unix seconds: refuse old ones                            |
| `webhook-signature` | `v1,` and the Base64 HMAC-SHA256 of `{webhook-id}.{webhook-timestamp}.{body}`           |

The HMAC key is the endpoint's `whsec_` secret without the prefix, Base64-decoded. Check the signature on the raw
body before parsing it; [WebhookVerifier](merchant-sim/src/main/java/dev/tkachuk/merchant/WebhookVerifier.java)
in merchant-sim shows how. Any 2xx answer counts as delivered. Anything else, a redirect included, is retried after
5 s, 5 min, 30 min, 2 h, 5 h and 10 h, each with up to a tenth more at random, then given up; 410 Gone is given up
at once. Webhooks can come twice and out of order, so go by the payment's or refund's `status` and `updatedAt`.

## Providers

`GATEWAY_PROVIDERS` lists the providers new payments go to, the preferred one first: `acquirer-sim` when it is not
set, `monobank,acquirer-sim` in compose. A payment goes on to the next provider only when the one before never got
the charge: the connection failed, or it was turned away unread with 429. A decline, a timeout or a 5xx stays with
the provider that had it, since the card may have been charged there. A provider whose charges keep getting no
answer or not getting through (half of its last ten, at least five) is skipped for 30 s, then two trial charges
show whether it is back. Gateway instances tell each other through Redis which providers they skip; without Redis
each instance goes by its own.

**acquirer-sim** has an API of its own. **monobank** charges through monobank's acquiring API with the card's
details (`payment-direct`): the payment's id goes as the invoice's reference, 3-D Secure sends the customer to
monobank's `tdsUrl` and back to `/3ds/return/{id}`, and monobank's error codes become the gateway's decline codes. A
charge whose answer was lost has no invoice id at the gateway yet, so the checker first finds it in monobank's
statement by its reference. When monobank can reach the gateway, it also reports invoice changes to
`/providers/monobank/webhooks`; the gateway checks each report's ECDSA signature against monobank's public key and
then looks the payment up, rather than taking the report's word.

A refund goes to the provider that charged the payment, whatever `GATEWAY_PROVIDERS` says now, and never on to
another one: money goes back the way it came. acquirer-sim decides a refund at once. monobank's `invoice/cancel`
answers "processing", and the gateway learns the outcome from monobank's webhook or, failing that, from the
invoice's `cancelList`, looked at 30 s after the refund and then every 10 s. A refund a provider still has no record
of after 10 minutes is `FAILED` with `provider_no_record`, as a payment is. Refunds don't go through the circuit
breakers: they have no other provider to go to.

In compose, monobank is **monobank-sim**: a second acquirer-sim, which also answers the part of monobank's API the
gateway uses, signed webhooks included. To use the real monobank instead, add these lines to `.env`, with your
token, and run `docker compose up -d` again; the webhook URL stays empty unless monobank can reach this gateway:

```
MONOBANK_URL=https://api.monobank.ua
GATEWAY_MONOBANK_TOKEN=
GATEWAY_MONOBANK_WEBHOOK_URL=
```

A test token from [api.monobank.ua](https://api.monobank.ua/) charges nothing: monobank accepts any card number that
passes the Luhn check and authorizes no money. monobank opens `payment-direct` only to merchants with a PCI DSS
certificate, so whether a token may use it is monobank's call; a refused request ends the payment as `FAILED` with
`provider_rejected_request`. The connector's tests run against WireMock with monobank's documented answers.

## Observability

Each service has its metrics at `/actuator/prometheus`, which Prometheus scrapes every 5 s
([prometheus.yml](observability/prometheus/prometheus.yml)); Grafana has Prometheus as its data source and the
[payment-gateway dashboard](observability/grafana/dashboards/payment-gateway.json), both provisioned from files. The
gateway counts:

| Metric                      | Labels                                                                                 |
|-----------------------------|----------------------------------------------------------------------------------------|
| `gateway_payments_total`    | `status`, `provider`, `code`: the decline code, `none` without one                     |
| `gateway_refunds_total`     | `status`, `provider`, `code`: the failure code, `none` without one                     |
| `gateway_risk_checks_total` | `answer`: `allowed`, `declined`, `unavailable` (an error, a timeout, its circuit open) |
| `gateway_webhooks_total`    | `outcome`: `delivered`, `retrying`, `given_up`                                         |

risk-service counts `risk_assessments_total` by `decision` and `reason`. Resilience4j publishes the circuit
breakers' state and calls (`resilience4j_circuitbreaker_*`, by `name`: each provider, and risk-service), and Spring
times the gateway's HTTP requests, in and out, and its gRPC calls as histograms, so the dashboard can show
percentiles.

Every service sends its traces over OTLP to Jaeger, which keeps them in memory, and traces every request, as a demo
can afford to. The trace context goes along with HTTP and gRPC calls, so one trace covers a payment's request, its
risk check and its charge. Outside compose, when `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is not set,
traces stay in the service: the gateway's startup log says which it is.

## Layout

```
gateway-service/   the merchant API, 3-D Secure return, webhooks, the risk check, the acquirer-sim and monobank
                   connectors
risk-service/      decides over gRPC whether a payment may go ahead; counts cards' payments in Redis
risk-api/          the gRPC contract between the two (risk.proto) and the Java code generated from it
acquirer-sim/      the simulated card acquirer with its challenge pages, a separate app with its own API and a
                   monobank-style one
merchant-sim/      the simulated shop: takes and checks webhooks, shows the page the customer comes back to
observability/     Prometheus' scrape settings, Grafana's data source and the dashboard
deploy/            the Helm chart with each environment's values (helm/), the local k3d cluster and its scripts (k3d/)
ci/                scripts for the pipeline: its tools, the coverage report, the chart's check, the images' build, scan
                   and release, the e2e job's logs
docs/              setting up GitLab, its runners, its registry and the GitHub mirror
```

How the parts fit, and why: [ARCHITECTURE.md](ARCHITECTURE.md).

## CI

The repository lives on [GitLab](https://gitlab.com/lecorrr/payment-gateway), whose own runners run the pipeline
([docs/gitlab-setup.md](docs/gitlab-setup.md)); the GitHub copy is a mirror of it. Every job is a gate: one that
fails stops the change there.

| Job     | What it checks                                                                          | Runs on    |
|---------|-----------------------------------------------------------------------------------------|------------|
| verify  | the build; unit and integration tests; 80 % line coverage per module (JaCoCo); SpotBugs with FindSecBugs | every push |
| chart   | `helm lint`, every environment's manifests against Kubernetes 1.35's schemas, values the chart must refuse | every push |
| secrets | every commit, for keys, tokens and passwords (gitleaks)                                 | every push |
| images  | the four images, for known HIGH or CRITICAL vulnerabilities that have a fix (Trivy); on main they are then pushed to the registry, tagged with the commit | every push |
| e2e     | those images, deployed to a k3d cluster inside the job and checked by `smoke-test.sh`   | main       |
| release | nothing more: it tags the commit's images with their version, such as `0.8.0`           | main       |

The coverage badge shows gateway-service's line coverage; the pipeline log has the other modules' too. How the
gates are set, and why: [ARCHITECTURE.md](ARCHITECTURE.md#pipeline).

## Next

Events over Kafka: the outbox's payment and refund events published to a topic, with a consumer of their own. Then
the AWS infrastructure `values-prod.yaml` expects, as Terraform, and a load test with measured latencies.
