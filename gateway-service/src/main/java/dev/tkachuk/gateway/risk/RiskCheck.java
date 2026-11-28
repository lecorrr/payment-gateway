package dev.tkachuk.gateway.risk;

import dev.tkachuk.gateway.common.Durations;
import dev.tkachuk.gateway.risk.RiskProperties.WhenUnavailable;
import dev.tkachuk.risk.api.AssessRequest;
import dev.tkachuk.risk.api.AssessResponse;
import dev.tkachuk.risk.api.RiskServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.StateTransition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks risk-service about a new payment before it is charged, waiting gateway.risk.deadline at most. An error or no
 * answer in time goes by gateway.risk.when-unavailable; so does a payment while risk-service's circuit is open,
 * which happens once it keeps failing, so payments stop waiting for its deadline. gateway.risk.checks counts the
 * answers: allowed, declined, or unavailable for every payment risk-service gave no decision for.
 */
@Component
public class RiskCheck {

    private static final Logger log = LoggerFactory.getLogger(RiskCheck.class);

    static final String CHECKS = "gateway.risk.checks";

    private final RiskServiceGrpc.RiskServiceBlockingStub risk;
    private final CircuitBreaker circuit;
    private final RiskProperties properties;
    private final Counter allowed;
    private final Counter declined;
    private final Counter unavailable;

    RiskCheck(RiskServiceGrpc.RiskServiceBlockingStub risk, CircuitBreaker riskServiceCircuit,
            RiskProperties properties, MeterRegistry meters) {
        this.risk = risk;
        this.circuit = riskServiceCircuit;
        this.properties = properties;
        this.allowed = checks(meters, "allowed");
        this.declined = checks(meters, "declined");
        this.unavailable = checks(meters, "unavailable");
        circuit.getEventPublisher().onStateTransition(event -> changed(event.getStateTransition()));
    }

    public RiskVerdict check(RiskRequest request) {
        if (!properties.enabled()) {
            return RiskVerdict.ALLOW;
        }
        if (!circuit.tryAcquirePermission()) {
            return unavailable(request, "is failing, so it isn't asked for now");
        }
        long start = circuit.getCurrentTimestamp();
        AssessResponse answer;
        try {
            answer = risk
                    .withDeadlineAfter(properties.deadline().toMillis(), TimeUnit.MILLISECONDS)
                    .assess(toAssessRequest(request));
        } catch (RuntimeException e) {
            circuit.onError(circuit.getCurrentTimestamp() - start, circuit.getTimestampUnit(), e);
            return unavailable(request, "did not answer: " + describe(e));
        }
        circuit.onSuccess(circuit.getCurrentTimestamp() - start, circuit.getTimestampUnit());

        return switch (answer.getDecision()) {
            case DECISION_ALLOW -> {
                allowed.increment();
                yield RiskVerdict.ALLOW;
            }
            case DECISION_DECLINE -> {
                log.info("risk-service declined payment {}: {}", request.paymentId(), answer.getReason());
                declined.increment();
                yield RiskVerdict.DECLINE;
            }
            default -> unavailable(request, "answered without a decision");
        };
    }

    private RiskVerdict unavailable(RiskRequest request, String why) {
        unavailable.increment();
        if (properties.whenUnavailable() == WhenUnavailable.DECLINE) {
            log.warn("Payment {} fails: risk-service {}", request.paymentId(), why);
            return RiskVerdict.FAIL;
        }
        log.warn("Payment {} goes ahead without a risk check: risk-service {}", request.paymentId(), why);
        return RiskVerdict.ALLOW;
    }

    private static Counter checks(MeterRegistry meters, String answer) {
        return Counter.builder(CHECKS)
                .description("Payments risk-service was asked about, by its answer")
                .tag("answer", answer)
                .register(meters);
    }

    private static AssessRequest toAssessRequest(RiskRequest request) {
        return AssessRequest.newBuilder()
                .setAttemptId(request.attemptId())
                .setPaymentId(request.paymentId().toString())
                .setMerchantId(request.merchantId().toString())
                .setAmount(request.amount())
                .setCurrency(request.currency().name())
                .setCardToken(request.cardToken())
                .setCardBin(request.cardBin())
                .build();
    }

    /** A failed call as its status code and description, or the exception's class for anything but a status. */
    static String describe(RuntimeException e) {
        if (e instanceof StatusRuntimeException statusException) {
            Status status = statusException.getStatus();
            return status.getDescription() == null
                    ? status.getCode().toString()
                    : status.getCode() + ", " + status.getDescription();
        }
        return e.getClass().getSimpleName();
    }

    private void changed(StateTransition transition) {
        switch (transition.getToState()) {
            case OPEN -> {
                CircuitBreaker.Metrics metrics = circuit.getMetrics();
                log.warn("risk-service is failing ({}% of its last {} answers): it isn't asked for {}",
                        Math.round(metrics.getFailureRate()), metrics.getNumberOfBufferedCalls(),
                        Durations.readable(properties.openFor()));
            }
            case HALF_OPEN -> log.info("risk-service gets a few payments again, to see whether it is back");
            case CLOSED -> log.info("risk-service is back: payments are checked again");
            default -> {
                // The other states are set by hand; the gateway never does.
            }
        }
    }
}
