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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks risk-service about a new payment before it is charged, waiting gateway.risk.deadline at most. An error or no
 * answer in time goes by gateway.risk.when-unavailable; so does a payment while risk-service's circuit is open,
 * which happens once it keeps failing, so payments stop waiting for its deadline.
 */
@Component
public class RiskCheck {

    private static final Logger log = LoggerFactory.getLogger(RiskCheck.class);

    private final RiskServiceGrpc.RiskServiceBlockingStub risk;
    private final CircuitBreaker circuit;
    private final RiskProperties properties;

    RiskCheck(RiskServiceGrpc.RiskServiceBlockingStub risk, CircuitBreaker riskServiceCircuit,
            RiskProperties properties) {
        this.risk = risk;
        this.circuit = riskServiceCircuit;
        this.properties = properties;
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
            case DECISION_ALLOW -> RiskVerdict.ALLOW;
            case DECISION_DECLINE -> {
                log.info("risk-service declined payment {}: {}", request.paymentId(), answer.getReason());
                yield RiskVerdict.DECLINE;
            }
            default -> unavailable(request, "answered without a decision");
        };
    }

    private RiskVerdict unavailable(RiskRequest request, String why) {
        if (properties.whenUnavailable() == WhenUnavailable.DECLINE) {
            log.warn("Payment {} fails: risk-service {}", request.paymentId(), why);
            return RiskVerdict.FAIL;
        }
        log.warn("Payment {} goes ahead without a risk check: risk-service {}", request.paymentId(), why);
        return RiskVerdict.ALLOW;
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

    private static String describe(RuntimeException e) {
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
