package dev.tkachuk.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignaturesTest {

    // The example the Standard Webhooks libraries are tested with; merchant-sim's tests check the same one.
    private static final WebhookSecret SECRET = WebhookSecret.parse("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
    private static final String MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final long TIMESTAMP = 1614265330L;
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);

    @Test
    void shouldSignAsStandardWebhooksDoes() {
        assertThat(WebhookSignatures.sign(SECRET, MESSAGE_ID, TIMESTAMP, BODY))
                .isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void shouldSignIdTimestampAndBodyEach() {
        String signature = WebhookSignatures.sign(SECRET, MESSAGE_ID, TIMESTAMP, BODY);

        assertThat(WebhookSignatures.sign(SECRET, "msg_other", TIMESTAMP, BODY)).isNotEqualTo(signature);
        assertThat(WebhookSignatures.sign(SECRET, MESSAGE_ID, TIMESTAMP + 1, BODY)).isNotEqualTo(signature);
        assertThat(WebhookSignatures.sign(SECRET, MESSAGE_ID, TIMESTAMP,
                "{\"test\": 2432232315}".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(signature);
    }
}
