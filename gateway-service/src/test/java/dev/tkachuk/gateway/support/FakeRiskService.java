package dev.tkachuk.gateway.support;

import dev.tkachuk.risk.api.AssessRequest;
import dev.tkachuk.risk.api.AssessResponse;
import dev.tkachuk.risk.api.Decision;
import dev.tkachuk.risk.api.RiskServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/** risk-service in the tests: allows every payment until a test says otherwise, and keeps what it was asked. */
public class FakeRiskService extends RiskServiceGrpc.RiskServiceImplBase {

    private static final AssessResponse ALLOW = AssessResponse.newBuilder()
            .setDecision(Decision.DECISION_ALLOW)
            .build();

    private final List<AssessRequest> requests = new CopyOnWriteArrayList<>();
    private volatile AssessResponse answer = ALLOW;
    // Null while it answers.
    private volatile @Nullable Status failure;
    private volatile Duration delay = Duration.ZERO;

    public void reset() {
        requests.clear();
        answer = ALLOW;
        failure = null;
        delay = Duration.ZERO;
    }

    public void decline(String reason) {
        answer = AssessResponse.newBuilder().setDecision(Decision.DECISION_DECLINE).setReason(reason).build();
    }

    public void answerWith(AssessResponse response) {
        answer = response;
    }

    public void failWith(Status status) {
        failure = status;
    }

    public void answerAfter(Duration delay) {
        this.delay = delay;
    }

    public List<AssessRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void assess(AssessRequest request, StreamObserver<AssessResponse> responseObserver) {
        requests.add(request);
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (responseObserver instanceof ServerCallStreamObserver<AssessResponse> call && call.isCancelled()) {
                // The gateway gave up waiting.
                return;
            }
        }
        Status failed = failure;
        if (failed != null) {
            responseObserver.onError(failed.asRuntimeException());
            return;
        }
        responseObserver.onNext(answer);
        responseObserver.onCompleted();
    }
}
