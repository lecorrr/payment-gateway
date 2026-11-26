package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SimPropertiesTest {

    private static final String KEY = "test-acquirer-key-0123456789";
    private static final String URL = "http://localhost:8091";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration REFUND_DELAY = Duration.ofSeconds(2);

    @Test
    void shouldNeedLongEnoughApiKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties("short", URL, Duration.ZERO, TTL, 10, REFUND_DELAY))
                .withMessageContaining("ACQUIRER_SIM_API_KEY");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(null, URL, Duration.ZERO, TTL, 10, REFUND_DELAY));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost:8091", "ftp://localhost"})
    void shouldNeedHttpPublicUrl(String publicUrl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, publicUrl, Duration.ZERO, TTL, 10, REFUND_DELAY))
                .withMessageContaining("public-url");
    }

    @Test
    void shouldDropTrailingSlashOfPublicUrl() {
        assertThat(new SimProperties(KEY, URL + "/", Duration.ZERO, TTL, 10, REFUND_DELAY).publicUrl())
                .isEqualTo(URL);
    }

    @Test
    void shouldRejectNegativeDelayAndEmptyStore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, URL, Duration.ofSeconds(-1), TTL, 10, REFUND_DELAY))
                .withMessageContaining("slow-delay");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, URL, Duration.ZERO, TTL, 0, REFUND_DELAY))
                .withMessageContaining("max-charges");
    }

    @Test
    void shouldNeedChallengeToStayOpenForAWhile() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, URL, Duration.ZERO, Duration.ZERO, 10, REFUND_DELAY))
                .withMessageContaining("challenge-ttl");
    }

    @Test
    void shouldHideApiKey() {
        assertThat(new SimProperties(KEY, URL, Duration.ZERO, TTL, 10, REFUND_DELAY).toString()).doesNotContain(KEY);
    }

    @Test
    void shouldRejectNegativeRefundDelay() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, URL, Duration.ZERO, TTL, 10, Duration.ofMillis(-1)))
                .withMessageContaining("refund-delay");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, URL, Duration.ZERO, TTL, 10, null))
                .withMessageContaining("refund-delay");
    }
}
