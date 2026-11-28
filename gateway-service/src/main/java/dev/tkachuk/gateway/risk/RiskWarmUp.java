package dev.tkachuk.gateway.risk;

import dev.tkachuk.gateway.common.Durations;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Asks risk-service for its health over the channel the risk checks use, before the gateway reports ready, so a new
 * gateway's first payment does not wait for what the channel's first call does: look risk-service's pods up, connect
 * to them, and load what the call goes through. Spring Boot reports ready once every ApplicationRunner has run. In the
 * e2e job's cluster that first call took longer than gateway.risk.deadline (300 ms), and its payment went ahead
 * without a risk check. When every pod starts at once, risk-service may not be ready yet, so the gateway asks again,
 * a second at a time, for a minute at most; then it starts anyway. With gateway.risk.enabled false it asks nothing.
 */
@Component
class RiskWarmUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskWarmUp.class);

    static final int ATTEMPTS = 60;
    static final Duration PER_ATTEMPT = Duration.ofSeconds(1);

    // How a call ends without an answer from risk-service: too late, no connection, or the gateway gave up on it.
    private static final Set<Status.Code> NO_ANSWER =
            Set.of(Status.Code.DEADLINE_EXCEEDED, Status.Code.UNAVAILABLE, Status.Code.CANCELLED);

    private final ManagedChannel channel;
    private final RiskProperties properties;

    RiskWarmUp(ManagedChannel riskChannel, RiskProperties properties) {
        this.channel = riskChannel;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.enabled()) {
            warmUp(ATTEMPTS, PER_ATTEMPT);
        }
    }

    /**
     * Asks risk-service up to attempts times, each time waiting at most perAttempt for the channel to connect and for
     * the answer; whether risk-service answered. Any answer will do, an error from risk-service too: it came over the
     * same channel, which is just as warm after it.
     */
    boolean warmUp(int attempts, Duration perAttempt) {
        HealthGrpc.HealthBlockingStub health = HealthGrpc.newBlockingStub(channel).withWaitForReady();
        long start = System.nanoTime();
        String lastFailure = "not asked";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HealthCheckResponse answer = health
                        .withDeadlineAfter(perAttempt.toMillis(), TimeUnit.MILLISECONDS)
                        .check(HealthCheckRequest.getDefaultInstance());
                warmedUp(start, answer.getStatus().toString());
                return true;
            } catch (StatusRuntimeException e) {
                if (!NO_ANSWER.contains(e.getStatus().getCode())) {
                    warmedUp(start, RiskCheck.describe(e));
                    return true;
                }
                lastFailure = RiskCheck.describe(e);
                if (attempt == 1) {
                    log.info("risk-service does not answer over gRPC yet ({}); asking again for up to {} before the "
                            + "gateway reports ready", lastFailure,
                            Durations.readable(perAttempt.multipliedBy(attempts)));
                }
                // Looks risk-service up again now, rather than after the channel's growing backoff.
                channel.resetConnectBackoff();
            }
        }
        log.warn("risk-service did not answer over gRPC in {} ms ({}); the first payments may wait longer for their "
                + "risk checks", millisSince(start), lastFailure);
        return false;
    }

    private static void warmedUp(long start, String answer) {
        log.info("Warmed up: risk-service answered over gRPC after {} ms ({})", millisSince(start), answer);
    }

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
