package dev.tkachuk.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.tkachuk.risk.api.AssessRequest;
import dev.tkachuk.risk.api.AssessResponse;
import dev.tkachuk.risk.api.Decision;
import dev.tkachuk.risk.api.RiskServiceGrpc;
import dev.tkachuk.risk.support.RedisTestcontainer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.client.GrpcChannelFactory;

/** risk-service as the gateway calls it, over in-process gRPC, with Redis in Testcontainers. */
@SpringBootTest(properties = {
    "risk.velocity.max-payments=3",
    "risk.velocity.window=10m",
    "risk.amount-limits.UAH=10000000",
    "risk.blocked-bins=400009"
})
@AutoConfigureTestGrpcTransport
@Import(RedisTestcontainer.class)
class RiskServiceIT {

    @Autowired
    private GrpcChannelFactory channels;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    private RiskServiceGrpc.RiskServiceBlockingStub risk;
    // A card of its own for each test, so no test sees another's payments.
    private String cardToken;

    @BeforeEach
    void connect() {
        risk = RiskServiceGrpc.newBlockingStub(channels.createChannel("risk-service"));
        cardToken = "tok_" + UUID.randomUUID();
    }

    @Test
    void shouldAllowPaymentThatBreaksNoRule() {
        AssessResponse answer = assess("order-1", "411111", 15000);

        assertThat(answer.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        assertThat(answer.getReason()).isEmpty();
    }

    @Test
    void shouldDeclineCardFromBlockedRange() {
        double before = assessments("decline", "blocked_card_range");

        assertDeclined(assess("order-1", "400009", 15000), "blocked_card_range");

        assertThat(assessments("decline", "blocked_card_range") - before).isEqualTo(1);
    }

    @Test
    void shouldDeclineAmountOverLimit() {
        assertDeclined(assess("order-1", "411111", 10_000_001), "amount_over_limit");
    }

    @Test
    void shouldDeclineCardThatPaysTooOften() {
        for (int order = 1; order <= 3; order++) {
            assertThat(assess("order-" + order, "411111", 15000).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        }

        assertDeclined(assess("order-4", "411111", 15000), "too_many_payments");
    }

    // A merchant retrying with the same Idempotency-Key, or a gateway asking again after a timeout.
    @Test
    void shouldCountRetryOfPaymentOnce() {
        for (int retry = 1; retry <= 5; retry++) {
            assertThat(assess("order-1", "411111", 15000).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        }

        assertThat(redis.opsForZSet().size(RedisVelocityCounter.KEY_PREFIX + cardToken)).isEqualTo(1);
    }

    @Test
    void shouldForgetCardOnceItsWindowIsOver() {
        assess("order-1", "411111", 15000);

        Long expiresInMillis = redis.getExpire(RedisVelocityCounter.KEY_PREFIX + cardToken, TimeUnit.MILLISECONDS);
        assertThat(expiresInMillis).isBetween(1L, TimeUnit.MINUTES.toMillis(10));
    }

    @Test
    void shouldRefuseRequestWithoutCardToken() {
        AssessRequest request = request("order-1", "411111", 15000).toBuilder().clearCardToken().build();

        assertThatExceptionOfType(StatusRuntimeException.class)
                .isThrownBy(() -> risk.assess(request))
                .satisfies(e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("card_token");
                });
    }

    private AssessResponse assess(String order, String cardBin, long amount) {
        return risk.assess(request(order, cardBin, amount));
    }

    private AssessRequest request(String order, String cardBin, long amount) {
        return AssessRequest.newBuilder()
                .setAttemptId("merchant-1/" + order)
                .setPaymentId(UUID.randomUUID().toString())
                .setMerchantId("merchant-1")
                .setAmount(amount)
                .setCurrency("UAH")
                .setCardToken(cardToken)
                .setCardBin(cardBin)
                .build();
    }

    private static void assertDeclined(AssessResponse answer, String reason) {
        assertThat(answer.getDecision()).isEqualTo(Decision.DECISION_DECLINE);
        assertThat(answer.getReason()).isEqualTo(reason);
    }

    private double assessments(String decision, String reason) {
        Counter counter = meters.find(RiskGrpcService.ASSESSMENTS)
                .tags("decision", decision, "reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
