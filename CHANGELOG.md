# Changelog

## 0.11.3 (2026-11-28)

- The pipeline's `e2e` job ran for the first time in 0.11.2, and the smoke test failed one check of 19: the first
  payment went ahead without a risk check. A new gateway's first call to risk-service does what the later ones
  don't: it looks risk-service's pods up, connects to them and loads what the call goes through. On the job's runner
  that took longer than the gateway's 300 ms deadline. Now, before it reports ready, the gateway asks risk-service
  for its health over the channel the risk checks use (`RiskWarmUp`, with gRPC's standard health service, which
  risk-service has). When every pod starts at once and risk-service isn't ready yet, the gateway asks again each
  second, for a minute at most, and then starts anyway.
- risk-service's warm-up connects to Redis before it assesses its made-up payment, trying up to five times. In the
  job's cluster the first connection took longer than Redis's 100 ms timeouts, so the made-up payment found no Redis,
  and risk-service then left Redis alone for 30 s: the first payments after the pod was ready weren't counted for
  the limit on payments per card.
- README: deploying the released images names 0.11.3.

## 0.11.2 (2026-11-28)

- The images job's Trivy scan ran for the first time in 0.11.1 and found HIGH vulnerabilities, each with a fix, in
  three libraries that other dependencies bring: lz4-java 1.11.2 and snappy-java 1.1.10.7, which come with Kafka's
  client (gateway-service and ledger-service), and Guava 33.6.0, which comes with gRPC (gateway-service and
  risk-service). pom.xml now manages them at the fixed versions: lz4-java 1.11.4, snappy-java 1.1.10.9 and Guava
  33.7.2. The images' Ubuntu packages had none.
- README: deploying the released images names 0.11.2.

## 0.11.1 (2026-11-28)

- The pipeline's `images` job failed on every commit since 0.9.2 (0.8.0, which brought it, stopped earlier, at
  verify), so the image scan, e2e and release never ran. It looked for the version in
  `BOOT-INF/classes/META-INF/build-info.properties`, but Spring Boot's repackaging leaves `META-INF/` at the jar's
  root, so the file is `META-INF/build-info.properties`. The job's `unzip` is BusyBox's, which prints nothing and
  succeeds for an entry a jar doesn't have, so the job reported an empty version instead of a missing file. It now
  reads the file at the root, and names the jar and the entry when it isn't there.
- README: deploying the released images names 0.11.1, since no earlier version got through the pipeline to a
  release.

## 0.11.0 (2026-11-27)

- `load-test`: a Gatling load test in Java that sends payments through an environment's Ingress at fixed rates
  rising in steps, after a warm-up 100, 200, 400, 800, 1600 and 3200 payments/s for two minutes each, until the knee:
  the first rate whose p99 passes 500 ms or whose failures pass 1 %. The run stops as soon as the knee is certain and
  prints each rate's failures and p50, p95 and p99. Every payment is a new Visa card, so risk-service's limit of ten
  payments per card never declines one.
- `deploy/k3d/load-test.sh staging` runs it against staging on k3d, with the demo merchant's key from the cluster,
  and reports how many gateway pods were ready during the run, the most CPU each app used and how busy the machine
  was. It runs on demand only; the pipeline's verify job compiles the load test and runs its unit tests.
- README: the load test's results on one desktop with 24 CPU threads. No payment failed, p99 stayed under 200 ms up
  to 800 payments/s, and the knee is at 1600 payments/s, with the machine's CPUs at most 63 % busy.

## 0.10.0 (2026-11-27)

- `deploy/aws`: the AWS of prod in Terraform, never applied. It has an EKS cluster (Kubernetes 1.37) in a VPC across
  three zones, the gateway's and the ledger's PostgreSQL 17 on RDS, Valkey on ElastiCache, Kafka 4.2 on MSK, and the
  secrets External Secrets reads, all as values-prod.yaml expects them. The VPC and the cluster come from
  terraform-aws-modules; the stores and the secrets come from four modules of this repository's own, each with tests
  of its rules.
- Each store has a range of the VPC to itself, and values-prod.yaml's NetworkPolicies let each app reach only its own
  stores' ranges, so the gateway can't reach the ledger's database on AWS either; before, they opened the stores'
  ports in the whole VPC. A test of `deploy/aws/prod` fails when values-prod.yaml and the network disagree.
- Terraform makes the databases' passwords but keeps them out of its state (write-only arguments). The chart can write
  its Secret through External Secrets from AWS Secrets Manager (`externalSecret`, on in values-prod.yaml).
- The pipeline's new `terraform` job checks `deploy/aws` without an AWS account: formatting, `terraform validate`,
  `terraform test` with mocked providers, tflint's AWS rules and Trivy. The `chart` job now checks prod against
  Kubernetes 1.37, the version of its EKS cluster, along with External Secrets' store for it.

## 0.9.3 (2026-11-27)

- A new risk-service pod's first assessment took about 0.5 s while every pod of staging started at once, past the
  gateway's 300 ms deadline, so that payment went ahead unchecked: staging's smoke test charged a card from the
  blocked range. risk-service now assesses one made-up payment through its own gRPC server before it reports ready
  (`WarmUp`), and the gateways call no pod before it is ready.
- The smoke test checks that risk-service answered for every payment in time, from the gateways' count of payments
  that went ahead unchecked, and names the gateway's reason when one did.

## 0.9.2 (2026-11-26)

The first full builds of 0.9.0 and 0.9.1 failed, so neither was released: this is the first release with what 0.9.0
describes.

- `./mvnw verify` failed at SpotBugs in 0.8.0 and 0.9.0: `RefundRepository.reservedAmount` checked for a null that
  can't come, since Spring 7's `JdbcClient` declares `single()` `@NonNull` and throws instead. The check is gone.
  The build stopped in gateway-service, so the modules after it were neither tested nor checked, and the pipeline
  fails the same way on 0.8.0.
- The ledger sorted a payment's entries of one moment by their ids, which are random, so `LedgerIT` failed about
  every other run: all its events carry one time. Such entries now come in the order they were booked, and a test
  whose refund id sorts before its payment's checks it.
- ledger-service logged `spring.kafka.bootstrap-servers` as where it reads from, which in a test is not where it
  connects: `@ServiceConnection` gives the consumer the container's address. It now logs the servers its consumer
  is built with.

## 0.9.0 (2026-11-26)

- Events on Kafka. Every status a payment or refund reaches is written to a second outbox, `event_outbox`
  (migration V4), in the transaction of the change, with the same id as its webhook and the trace context of the
  request that made it. `EventPublisher` sends them to the topic `payment-events` as CloudEvents 1.0 in Kafka's
  binary mode, keyed by the payment's id: one gateway at a time (an advisory lock), in order, at least once, an event
  marked published only once Kafka and every event before it were taken. While Kafka is down, payments go on and
  their events wait; the publisher tries again after 1 s, then twice as long each time up to 30 s. It makes the topic
  as soon as Kafka answers, and deletes published events after seven days. The producer is idempotent with
  `acks=all`, and its and the admin client's timeouts are seconds, not the clients' default minute.
- The topic's contract, [docs/payment-events.yaml](docs/payment-events.yaml), in AsyncAPI 3.0, with examples:
  the gateway's tests check its records against the schemas, the ledger's book its examples.
- ledger-service, a new app: it reads `payment-events` as consumer group `ledger` and books each payment and refund
  that succeeded into a double-entry ledger in a PostgreSQL of its own, once per event (it notes the event's id in
  the booking's transaction), whatever Kafka delivers twice. An event it can't read goes to
  `payment-events-ledger-dlt` with the reason in its headers; any other failure is tried again, with backoff and
  jitter, until it works. Its internal API shows a payment's entries and a merchant's balances, and it continues
  the trace of the request that made the change.
- compose runs Kafka (one KRaft node), the ledger and its database; the ledger's API is on `LEDGER_PORT` (8095).
- The Helm chart runs Kafka as a StatefulSet in the namespace (`kafka.*`), or points the apps at a cluster of their
  own, as MSK over TLS in `values-prod.yaml`, and the ledger with its own PostgreSQL (`ledger.*`); staging runs two
  ledgers with a disruption budget. The network policies let the gateway and the ledger reach Kafka, the ledger its
  database, and nothing reach the ledger's API. `ci/check-chart.sh` also renders dev without the ledger, and checks
  that the chart refuses a ledger or Kafka set up wrong.
- `deploy/k3d/deploy.sh` builds and loads the ledger's image too, and adds the keys a newer version needs to a
  Secret an older one made, here `LEDGER_DB_PASSWORD`. `deploy/k3d/smoke-test.sh` checks that the ledger booked a
  payment and its refund, and the network policies around Kafka and the ledger's database. The pipeline builds,
  scans, pushes and releases the ledger's image with the others.
- The dashboard has a row for the events and the ledger: events to Kafka, the outbox's lag, what the ledger took,
  its consumer lag and its time per event.
- The gateway runs its background tasks on four threads instead of one, so the webhook dispatcher, the payment and
  refund checkers and the event publisher don't wait on each other.
- `MigrationIT` upgrades a database of 0.8.0, with a payment and a refund in it, to this version's schema.

## 0.8.0 (2026-11-26)

- Quality gates in the pipeline, each of which stops a change that fails it:
  - `verify` also runs SpotBugs with FindSecBugs' security rules over every module's classes, and a finding of
    medium priority or above fails the build. `spotbugs-exclude.xml` holds the four exceptions, each with its
    reason; risk-api, all generated code, is left out.
  - A `secrets` job searches every commit with gitleaks; `.gitleaks.toml` leaves out the tests' made-up keys.
  - The `images` job scans each image with Trivy, its Ubuntu packages and the Java libraries in its jar: a known
    HIGH or CRITICAL vulnerability that has a fix fails it. On main it then pushes the images to the project's
    container registry, tagged with the commit's SHA.
  - On main, an `e2e` job deploys exactly those images as dev to a one-node k3d cluster inside the job
    (`deploy/k3d/cluster-ci.yaml`) and runs the smoke test against them; the cluster's state and every pod's log
    are its artifacts. Then a `release` job tags the images with their version, such as `:0.8.0`, as a copy of the
    same manifest, so the released image is the one the checks saw. A version is released once: a later commit of
    the same version keeps only its commit tag.
- The pipeline runs for a merge request instead of its branch when one is open, and a newer push cancels the jobs
  of the pipeline before it that haven't finished, except a release. Every tool it downloads comes from
  `ci/install-tools.sh`, in a fixed version and checked against its published checksum.
- Docker-in-Docker runs with the MTU of GitLab's small runners, 1360, on its default network and on the ones made in
  it, such as k3d's: with Docker's 1500, a container inside it sends packets too big to leave the runner.
- Fixed what the static analysis found:
  - merchant-sim writes the webhook id and the ids and types from a webhook into its log with line breaks replaced,
    so a crafted request can't forge log lines of its own;
  - acquirer-sim draws authorization codes from `SecureRandom`, as a bank's should not be guessable;
  - where a field that may be missing was checked on one read and used on another (in the connectors, the monobank
    webhook, the payment service, the request fingerprints and the webhook dispatcher), it is read once and that
    value is checked and used. Nothing behaves differently, but SpotBugs can now see it is safe;
  - the API key filter, which writes its 401 before Spring MVC, gives a problem without a type as `about:blank`
    (RFC 9457) rather than failing on it; its own problems always have one;
  - three classes whose constructors may throw are final: `GatewayException`, `ProviderConnectors` and
    acquirer-sim's `MonobankKeys`.
- The code generated from the OpenAPI contract uses JSpecify's `@Nullable`, as Spring Framework 7 does, instead of
  Spring's deprecated one; SpotBugs reads it.
- `deploy/k3d/deploy.sh` deploys a registry's images with `IMAGE_REGISTRY` and `IMAGE_TAG`, such as a released
  version or a commit's images, without building anything. It loads images in k3d's direct mode, which fails if a
  node refuses an image; for a Docker on another machine k3d would otherwise pick a mode that only logs that.
- `deploy/k3d/smoke-test.sh`: the rollout check pays until the old gateways are gone, not only until
  `kubectl rollout status` returns, since they still finish requests while they stop; the version check and the
  risk-service check leave out pods that are already stopping, which a check right after a deploy saw;
  `INGRESS_ADDRESS` sends the requests for the Ingress to another address, and `SMOKE_TEST_LOG` names the log. It
  runs with BusyBox's tools, as in the pipeline's Alpine images, as well as with GNU's.
- `deploy/k3d/create-cluster.sh` takes a k3d config file, and checks that port 8280 is free only when Docker runs on
  this machine. `helm test` tries each app quietly and prints one line for it.
- `ci/check-chart.sh` also renders dev with a commit's images from the registry, as the e2e job deploys it.

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
