package dev.tkachuk.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class MerchantSimPropertiesTest {

    private static final String SECRET = WebhookVerifierTest.SECRET;
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @ParameterizedTest
    @NullAndEmptySource
    void shouldNeedSecret(String secret) {
        assertThatIllegalArgumentException().isThrownBy(() -> new MerchantSimProperties(secret, TOLERANCE, 0, 100))
                .withMessageContaining("MERCHANT_SIM_WEBHOOK_SECRET");
    }

    @Test
    void shouldRejectValuesOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MerchantSimProperties(SECRET, Duration.ZERO, 0, 100))
                .withMessageContaining("tolerance");
        assertThatIllegalArgumentException().isThrownBy(() -> new MerchantSimProperties(SECRET, TOLERANCE, -1, 100))
                .withMessageContaining("fail-first");
        assertThatIllegalArgumentException().isThrownBy(() -> new MerchantSimProperties(SECRET, TOLERANCE, 0, 0))
                .withMessageContaining("max-events");
    }

    @Test
    void shouldHideSecret() {
        assertThat(new MerchantSimProperties(SECRET, TOLERANCE, 0, 100).toString()).doesNotContain(SECRET);
    }
}
