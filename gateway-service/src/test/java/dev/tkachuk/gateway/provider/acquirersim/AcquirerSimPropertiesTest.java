package dev.tkachuk.gateway.provider.acquirersim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AcquirerSimPropertiesTest {

    private static final String URL = "http://localhost:8091";
    private static final String KEY = "test-acquirer-key-0123456789";
    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost:8091", "ftp://localhost"})
    void shouldNeedHttpUrl(String url) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AcquirerSimProperties(url, KEY, TWO_SECONDS, FIVE_SECONDS))
                .withMessageContaining("base-url");
    }

    @Test
    void shouldBeConfiguredWithApiKey() {
        AcquirerSimProperties properties = new AcquirerSimProperties(URL, " " + KEY + "\n", TWO_SECONDS,
                FIVE_SECONDS);

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.apiKey()).isEqualTo(KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void shouldBeUnconfiguredWithoutApiKey(String apiKey) {
        assertThat(new AcquirerSimProperties(URL, apiKey, TWO_SECONDS, FIVE_SECONDS).isConfigured()).isFalse();
    }

    @Test
    void shouldRefuseApiKeyWithSpaceInIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AcquirerSimProperties(URL, "test acquirer key", TWO_SECONDS, FIVE_SECONDS))
                .withMessageContaining("ACQUIRER_SIM_API_KEY");
    }

    @Test
    void shouldNeedReadTimeoutLongerThanConnectTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AcquirerSimProperties(URL, KEY, FIVE_SECONDS, FIVE_SECONDS))
                .withMessageContaining("read-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AcquirerSimProperties(URL, KEY, Duration.ZERO, FIVE_SECONDS))
                .withMessageContaining("connect-timeout");
    }

    @Test
    void shouldHideApiKey() {
        assertThat(new AcquirerSimProperties(URL, KEY, TWO_SECONDS, FIVE_SECONDS).toString())
                .contains(URL)
                .doesNotContain(KEY);
    }
}
