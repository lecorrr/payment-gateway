package dev.tkachuk.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Payments at fixed arrival rates that rise in steps, through the gateway's public API: a warm-up, then each rate of
 * LOAD_STEPS for LOAD_STEP_SECONDS, one after another. New payments arrive at the rate whether or not the gateway has
 * answered the earlier ones (an open model), as merchants' customers do, so a gateway that falls behind shows it in
 * its response times instead of slowing the test down. Each payment is a new card's 150.00 UAH, which the simulators
 * approve: a success is a 201 with status SUCCEEDED. The run stops at the knee, the first rate whose p99 passes
 * LOAD_P99_BUDGET_MS or whose failures pass 1 %, as soon as that is certain, and then prints each rate's p50, p95 and
 * p99 and the knee.
 */
public final class PaymentsSimulation extends Simulation {

    static final int WARM_UP_RATE = 10;
    static final Duration WARM_UP = Duration.ofSeconds(30);
    static final double MAX_FAILED_PERCENT = 1.0;
    private static final String RESPONSE_TIME = "responseTime";

    private final LoadSettings settings = LoadSettings.from(System.getenv());
    private final StepStats stats = new StepStats(settings.rates(), settings.stepDuration(), settings.p99Budget(),
            MAX_FAILED_PERCENT);
    // Another place in the card range for each run, so as not to reuse the last run's cards.
    private final NewPayments newPayments = new NewPayments(Long.toString(System.currentTimeMillis(), 36),
            new TestCards(ThreadLocalRandom.current().nextLong(900_000_000L)));

    public PaymentsSimulation() {
        HttpProtocolBuilder gateway = http.baseUrl(settings.gatewayUrl().toString())
                // To the Ingress's address, with the gateway's name in the Host header, by which the Ingress finds
                // it: as INGRESS_ADDRESS does for the smoke test.
                .hostNameAliases(Map.of(settings.gatewayUrl().getHost(), List.of(settings.ingressAddress())))
                // One pool of kept-alive connections, as a merchant's server has, rather than one per payment.
                .shareConnections()
                // Gatling's own warm-up request goes to gatling.io; the warm-up rate below is the gateway's.
                .disableWarmUp()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader("payment-gateway-load-test");

        List<PopulationBuilder> steps = new ArrayList<>();
        steps.add(payments("warm-up", null).injectOpen(constantUsersPerSec(WARM_UP_RATE).during(WARM_UP)));
        for (int rate : settings.rates()) {
            steps.add(payments(rate + "/s", rate)
                    .injectOpen(constantUsersPerSec(rate).during(settings.stepDuration())));
        }

        // The lowest rate must keep within the budget: if it doesn't, the environment isn't well, and the run fails.
        String lowest = requestName(settings.rates().getFirst() + "/s");
        setUp(oneAfterAnother(steps))
                .protocols(gateway)
                .assertions(
                        details(lowest).failedRequests().percent().lte(MAX_FAILED_PERCENT),
                        details(lowest).responseTime().percentile(99.0).lte((int) settings.p99Budget().toMillis()));
    }

    @Override
    public void before() {
        Duration total = WARM_UP.plus(settings.stepDuration().multipliedBy(settings.rates().size()));
        System.out.printf(Locale.ROOT,
                "load-test: %s, a warm-up of %d s at %d payments/s, then %s payments/s for %d s each, at most %d min,"
                        + " as the run stops at the knee%n",
                settings.gatewayUrl(), WARM_UP.toSeconds(), WARM_UP_RATE, settings.rates(),
                settings.stepDuration().toSeconds(), Math.ceilDiv(total.toSeconds(), 60));
    }

    @Override
    public void after() {
        System.out.print(stats.report());
    }

    // A step's payments, recorded under the rate; the warm-up's (rate null) are not. Each of a step's payments first
    // asks whether the knee is known, and the first to find it stops the run: the steps past the knee would only pile
    // up payments the gateway can't take.
    private ScenarioBuilder payments(String step, Integer rate) {
        ScenarioBuilder scenario = scenario(step);
        if (rate != null) {
            scenario = scenario.stopLoadGeneratorIf("the knee is known", session -> stats.kneeFound(rate));
        }
        return scenario
                .feed(newPayments)
                .exec(http(requestName(step))
                        .post("/api/v1/payments")
                        .header("X-Api-Key", settings.apiKey())
                        .header("Idempotency-Key", session -> session.getString(NewPayments.IDEMPOTENCY_KEY))
                        .body(StringBody(session -> session.getString(NewPayments.BODY)))
                        .check(
                                // Saved whatever the other checks find: Gatling applies every check.
                                responseTimeInMillis().saveAs(RESPONSE_TIME),
                                status().is(201),
                                jsonPath("$.status").is("SUCCEEDED")))
                .exec(session -> record(rate, session));
    }

    private Session record(Integer rate, Session session) {
        if (rate != null) {
            Integer responseTimeMs = session.contains(RESPONSE_TIME) ? session.getInt(RESPONSE_TIME) : null;
            stats.record(rate, responseTimeMs, !session.isFailed());
        }
        return session;
    }

    private static String requestName(String step) {
        return "pay at " + step;
    }

    // Each step starts when the one before has ended, its last answers included.
    private static PopulationBuilder oneAfterAnother(List<PopulationBuilder> steps) {
        PopulationBuilder chain = steps.getLast();
        for (int i = steps.size() - 2; i >= 0; i--) {
            chain = steps.get(i).andThen(chain);
        }
        return chain;
    }
}
