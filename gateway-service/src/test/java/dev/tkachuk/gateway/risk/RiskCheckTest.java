package dev.tkachuk.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.risk.RiskProperties.WhenUnavailable;
import dev.tkachuk.gateway.support.FakeRiskService;
import dev.tkachuk.gateway.support.MovableClock;
import dev.tkachuk.risk.api.AssessRequest;
import dev.tkachuk.risk.api.AssessResponse;
import dev.tkachuk.risk.api.Decision;
import dev.tkachuk.risk.api.RiskServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a real gRPC server in-process: answers, errors, the deadline and the circuit. */
class RiskCheckTest {

    private static final Duration OPEN_FOR = Duration.ofSeconds(30);
    private static final RiskProperties ALLOW_WHEN_UNAVAILABLE =
            new RiskProperties(true, Duration.ofMillis(300), WhenUnavailable.ALLOW, OPEN_FOR);
    private static final RiskProperties DECLINE_WHEN_UNAVAILABLE =
            new RiskProperties(true, Duration.ofMillis(300), WhenUnavailable.DECLINE, OPEN_FOR);
    private static final UUID PAYMENT_ID = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("0b6a1d2e-0000-4000-8000-000000000002");
    private static final RiskRequest REQUEST = new RiskRequest(MERCHANT_ID + "/order-1001-1", PAYMENT_ID, MERCHANT_ID,
            15000, Currency.UAH, "c2F0aXNmaWVkLWNhcmQtdG9rZW4tZm9yLXRlc3RzLWFhYQ", "411111");

    private final FakeRiskService riskService = new FakeRiskService();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(riskService).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void shouldAllowPaymentRiskServiceAllows() {
        assertThat(riskCheck(ALLOW_WHEN_UNAVAILABLE).check(REQUEST)).isEqualTo(RiskVerdict.ALLOW);

        AssessRequest asked = riskService.requests().getFirst();
        assertThat(asked.getAttemptId()).isEqualTo(MERCHANT_ID + "/order-1001-1");
        assertThat(asked.getPaymentId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(asked.getMerchantId()).isEqualTo(MERCHANT_ID.toString());
        assertThat(asked.getAmount()).isEqualTo(15000);
        assertThat(asked.getCurrency()).isEqualTo("UAH");
        assertThat(asked.getCardToken()).isEqualTo(REQUEST.cardToken());
        assertThat(asked.getCardBin()).isEqualTo("411111");
        assertThat(checks("allowed")).isEqualTo(1);
    }

    @Test
    void shouldDeclinePaymentRiskServiceDeclines() {
        riskService.decline("too_many_payments");

        assertThat(riskCheck(ALLOW_WHEN_UNAVAILABLE).check(REQUEST)).isEqualTo(RiskVerdict.DECLINE);
        assertThat(checks("declined")).isEqualTo(1);
    }

    @Test
    void shouldGoBySettingWhenRiskServiceFails() {
        riskService.failWith(Status.UNAVAILABLE.withDescription("risk-service is restarting"));

        assertThat(riskCheck(ALLOW_WHEN_UNAVAILABLE).check(REQUEST)).isEqualTo(RiskVerdict.ALLOW);
        assertThat(riskCheck(DECLINE_WHEN_UNAVAILABLE).check(REQUEST)).isEqualTo(RiskVerdict.FAIL);
        assertThat(checks("unavailable")).isEqualTo(2);
        assertThat(checks("allowed")).isZero();
    }

    @Test
    void shouldStopWaitingAtDeadline() {
        riskService.answerAfter(Duration.ofSeconds(2));
        long start = System.nanoTime();

        RiskVerdict verdict = riskCheck(DECLINE_WHEN_UNAVAILABLE).check(REQUEST);

        assertThat(verdict).isEqualTo(RiskVerdict.FAIL);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void shouldTakeAnswerWithoutDecisionForNoAnswer() {
        riskService.answerWith(AssessResponse.newBuilder().setDecision(Decision.DECISION_UNSPECIFIED).build());

        assertThat(riskCheck(DECLINE_WHEN_UNAVAILABLE).check(REQUEST)).isEqualTo(RiskVerdict.FAIL);
    }

    // Five failures of five answers: payments stop waiting for it, until open-for has passed.
    @Test
    void shouldLeaveRiskServiceThatKeepsFailingAloneForOpenFor() {
        riskService.failWith(Status.UNAVAILABLE);
        RiskCheck riskCheck = riskCheck(ALLOW_WHEN_UNAVAILABLE);
        for (int payment = 1; payment <= 5; payment++) {
            riskCheck.check(REQUEST);
        }

        assertThat(riskCheck.check(REQUEST)).isEqualTo(RiskVerdict.ALLOW);
        assertThat(riskService.requests()).hasSize(5);
        assertThat(meters.get("resilience4j.circuitbreaker.state").tags("name", "risk-service", "state", "open")
                .gauge().value()).isEqualTo(1);

        clock.move(OPEN_FOR.plusSeconds(1));
        riskService.reset();
        assertThat(riskCheck.check(REQUEST)).isEqualTo(RiskVerdict.ALLOW);
        assertThat(riskService.requests()).hasSize(1);
    }

    @Test
    void shouldNotAskWhenRiskChecksAreOff() {
        RiskProperties off = new RiskProperties(false, Duration.ofMillis(300), WhenUnavailable.DECLINE, OPEN_FOR);

        assertThat(riskCheck(off).check(REQUEST)).isEqualTo(RiskVerdict.ALLOW);
        assertThat(riskService.requests()).isEmpty();
    }

    @Test
    void shouldKeepCardTokenOutOfLog() {
        assertThat(REQUEST.toString()).doesNotContain(REQUEST.cardToken()).doesNotContain("411111");
    }

    private RiskCheck riskCheck(RiskProperties properties) {
        return new RiskCheck(RiskServiceGrpc.newBlockingStub(channel),
                new RiskClientConfiguration().riskServiceCircuit(properties, clock, meters), properties, meters);
    }

    private double checks(String answer) {
        return meters.counter(RiskCheck.CHECKS, "answer", answer).count();
    }
}
