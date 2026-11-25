package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentPropertiesTest {

    private static final String KEY = TestPayments.FINGERPRINT_KEY;
    private static final String URL = TestPayments.PUBLIC_URL;
    private static final Duration SECOND = Duration.ofSeconds(1);

    @ParameterizedTest
    @ValueSource(strings = {"", "only-31-characters-long-0123456"})
    void shouldRejectShortFingerprintKey(String key) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(key, URL, SECOND, SECOND, SECOND, SECOND, 20))
                .withMessageContaining("fingerprint-key");
    }

    @Test
    void shouldRejectMissingFingerprintKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(null, URL, SECOND, SECOND, SECOND, SECOND, 20));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost:8090", "ftp://gateway.test"})
    void shouldNeedHttpPublicUrl(String publicUrl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, publicUrl, SECOND, SECOND, SECOND, SECOND, 20))
                .withMessageContaining("public-url");
    }

    @Test
    void shouldDropTrailingSlashOfPublicUrl() {
        assertThat(new PaymentProperties(KEY, URL + "/", SECOND, SECOND, SECOND, SECOND, 20).publicUrl())
                .isEqualTo(URL);
    }

    @Test
    void shouldRejectDurationsThatAreNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, URL, Duration.ZERO, SECOND, SECOND, SECOND, 20))
                .withMessageContaining("first-check-after");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, URL, SECOND, Duration.ofSeconds(-1), SECOND, SECOND, 20))
                .withMessageContaining("check-interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, URL, SECOND, SECOND, Duration.ZERO, SECOND, 20))
                .withMessageContaining("challenge-check-interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, URL, SECOND, SECOND, SECOND, null, 20))
                .withMessageContaining("give-up-after");
    }

    @Test
    void shouldRejectEmptyBatch() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentProperties(KEY, URL, SECOND, SECOND, SECOND, SECOND, 0))
                .withMessageContaining("check-batch-size");
    }

    @Test
    void shouldHideFingerprintKey() {
        assertThat(TestPayments.properties().toString()).contains("checkInterval", URL).doesNotContain(KEY);
    }
}
