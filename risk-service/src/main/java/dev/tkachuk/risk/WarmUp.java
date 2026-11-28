package dev.tkachuk.risk;

import dev.tkachuk.risk.api.AssessRequest;
import dev.tkachuk.risk.api.AssessResponse;
import dev.tkachuk.risk.api.RiskServiceGrpc;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * Assesses one made-up payment through this risk-service's own gRPC server before risk-service reports ready, so a
 * new pod's first payment does not wait for what its first assessment loads. Spring Boot reports ready once every
 * ApplicationRunner has run, and the gateways only call pods that are ready. While every pod of staging started at
 * once, a pod's first assessment took about 0.5 s, past the gateway's 300 ms deadline, and that payment went ahead
 * unchecked. The warm-up shows as an allowed assessment of payment "warm-up".
 *
 * <p>First it connects to Redis, trying a few times: the first connection also loads what Lettuce needs, which in the
 * e2e job's cluster took longer than spring.data.redis's timeouts (100 ms). When the made-up assessment met that, the
 * counter left Redis alone for its pause of 30 s (RedisVelocityCounter), and counted none of the first payments after
 * the pod was ready.
 */
@Component
class WarmUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    /** The channel to this risk-service, at spring.grpc.client.channel.self.target. */
    static final String CHANNEL = "self";

    static final Duration DEADLINE = Duration.ofSeconds(5);

    /** Tries at connecting to Redis; each can take spring.data.redis's connect and command timeouts. */
    static final int REDIS_ATTEMPTS = 5;

    /** A payment no gateway sends: its card's count in Redis has a key of its own, which ends with the window. */
    static final AssessRequest MADE_UP_PAYMENT = AssessRequest.newBuilder()
            .setAttemptId("warm-up")
            .setPaymentId("warm-up")
            .setMerchantId("warm-up")
            .setAmount(1)
            .setCurrency("UAH")
            .setCardToken("warm-up")
            .setCardBin("000000")
            .build();

    private final GrpcChannelFactory channels;
    private final StringRedisTemplate redis;

    WarmUp(GrpcChannelFactory channels, StringRedisTemplate redis) {
        this.channels = channels;
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        connectRedis();
        assessMadeUpPayment();
    }

    private void connectRedis() {
        long start = System.nanoTime();
        String lastFailure = "not asked";
        for (int attempt = 1; attempt <= REDIS_ATTEMPTS; attempt++) {
            try {
                // The connection stays open, shared by every command after it.
                String answer = redis.execute((RedisConnection connection) -> connection.ping());
                log.info("Connected to Redis in {} ms, at try {} ({})", millisSince(start), attempt, answer);
                return;
            } catch (RuntimeException e) {
                lastFailure = e.toString();
            }
        }
        // Never a reason not to start either: without Redis the counter only leaves the velocity rule out, and says so.
        log.warn("Could not connect to Redis in {} tries, {} ms ({})", REDIS_ATTEMPTS, millisSince(start),
                lastFailure);
    }

    private void assessMadeUpPayment() {
        long start = System.nanoTime();
        try {
            // The factory keeps the channel and closes it when risk-service stops.
            AssessResponse answer = RiskServiceGrpc.newBlockingStub(channels.createChannel(CHANNEL))
                    .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                    .assess(MADE_UP_PAYMENT);
            log.info("Warmed up: a made-up payment's assessment over gRPC took {} ms ({})", millisSince(start),
                    answer.getDecision());
        } catch (RuntimeException e) {
            // Never a reason not to start: without the warm-up, the first payments only wait longer for an answer.
            log.warn("Could not warm up after {} ms ({}); the first payments may wait longer for an answer",
                    millisSince(start), e.toString());
        }
    }

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
