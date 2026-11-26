package dev.tkachuk.risk;

import dev.tkachuk.risk.api.AssessRequest;
import dev.tkachuk.risk.api.AssessResponse;
import dev.tkachuk.risk.api.Decision;
import dev.tkachuk.risk.api.RiskServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

/**
 * The RiskService of risk.proto. A request that is not a payment the rules can judge is refused with
 * INVALID_ARGUMENT, naming the field; the gateway then goes by its own setting for when risk-service can't answer.
 */
@GrpcService
class RiskGrpcService extends RiskServiceGrpc.RiskServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(RiskGrpcService.class);

    private final RiskRules rules;

    RiskGrpcService(RiskRules rules) {
        this.rules = rules;
    }

    @Override
    public void assess(AssessRequest request, StreamObserver<AssessResponse> responseObserver) {
        PaymentAttempt attempt;
        try {
            attempt = new PaymentAttempt(request.getAttemptId(), request.getPaymentId(), request.getMerchantId(),
                    request.getAmount(), request.getCurrency(), request.getCardToken(), request.getCardBin());
        } catch (IllegalArgumentException e) {
            log.warn("Refused to assess payment {}: {}", request.getPaymentId(), e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }

        Assessment assessment = rules.assess(attempt);
        AssessResponse.Builder response = AssessResponse.newBuilder();
        if (assessment.allowed()) {
            log.info("Payment {} of merchant {} allowed", attempt.paymentId(), attempt.merchantId());
            response.setDecision(Decision.DECISION_ALLOW);
        } else {
            log.info("Payment {} of merchant {} declined: {}", attempt.paymentId(), attempt.merchantId(),
                    assessment.reason());
            response.setDecision(Decision.DECISION_DECLINE).setReason(assessment.reason());
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}
