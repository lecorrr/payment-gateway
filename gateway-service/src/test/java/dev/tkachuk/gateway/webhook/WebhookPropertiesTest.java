package dev.tkachuk.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookPropertiesTest {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(5), Duration.ofMinutes(5));

    @Test
    void shouldRejectDurationsThatAreNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookProperties(Duration.ZERO, 20, SECOND, SECOND, DELAYS))
                .withMessageContaining("dispatch-interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookProperties(SECOND, 20, null, SECOND, DELAYS))
                .withMessageContaining("connect-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookProperties(SECOND, 20, SECOND, Duration.ofSeconds(-1), DELAYS))
                .withMessageContaining("read-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookProperties(SECOND, 20, SECOND, SECOND, List.of(SECOND, Duration.ZERO)))
                .withMessageContaining("retry-delays");
    }

    @Test
    void shouldRejectEmptyBatchAndMissingDelays() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookProperties(SECOND, 0, SECOND, SECOND, DELAYS))
                .withMessageContaining("batch-size");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookProperties(SECOND, 20, SECOND, SECOND, null))
                .withMessageContaining("retry-delays");
    }

    @Test
    void shouldKeepItsOwnCopyOfDelays() {
        List<Duration> delays = new ArrayList<>(DELAYS);
        WebhookProperties properties = new WebhookProperties(SECOND, 20, SECOND, SECOND, delays);
        delays.clear();

        assertThat(properties.retryDelays()).isEqualTo(DELAYS);
    }

    // Long enough for the whole batch to hit both timeouts, plus a margin.
    @Test
    void shouldLeaseEventsForWholeBatch() {
        WebhookProperties properties = new WebhookProperties(SECOND, 20, Duration.ofSeconds(2),
                Duration.ofSeconds(15), DELAYS);

        assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(17 * 20 + 30));
    }
}
