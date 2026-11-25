package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentPropertiesTest {

    private static final String KEY = TestPayments.FINGERPRINT_KEY;
    private static final Duration SECOND = Duration.ofSeconds(1);

    @ParameterizedTest
    @ValueSource(strings = {"", "only-31-characters-long-0123456"})
    void shouldRejectShortFingerprintKey(String key) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(key, SECOND, SECOND, SECOND, 20))
                .withMessageContaining("fingerprint-key");
    }

    @Test
    void shouldRejectMissingFingerprintKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentProperties(null, SECOND, SECOND, SECOND, 20));
    }

    @Test
    void shouldRejectDurationsThatAreNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, Duration.ZERO, SECOND, SECOND, 20))
                .withMessageContaining("first-check-after");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, SECOND, Duration.ofSeconds(-1), SECOND, 20))
                .withMessageContaining("check-interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, SECOND, SECOND, null, 20))
                .withMessageContaining("give-up-after");
    }

    @Test
    void shouldRejectEmptyBatch() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, SECOND, SECOND, SECOND, 0))
                .withMessageContaining("check-batch-size");
    }

    @Test
    void shouldHideFingerprintKey() {
        assertThat(TestPayments.properties().toString()).contains("checkInterval").doesNotContain(KEY);
    }
}
