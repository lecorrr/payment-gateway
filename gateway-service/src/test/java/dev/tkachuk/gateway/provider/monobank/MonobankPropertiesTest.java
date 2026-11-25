package dev.tkachuk.gateway.provider.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonobankPropertiesTest {

    private static final String BASE_URL = "https://api.monobank.ua";
    private static final String TOKEN = "test-monobank-token";
    private static final String WEBHOOK_URL = "https://gateway.example.com/providers/monobank/webhooks";
    private static final Duration CONNECT = Duration.ofSeconds(2);
    private static final Duration READ = Duration.ofSeconds(10);

    @Test
    void shouldBeConfiguredWithToken() {
        MonobankProperties properties = new MonobankProperties(BASE_URL, " " + TOKEN + "\n", WEBHOOK_URL, CONNECT,
                READ);

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.token()).isEqualTo(TOKEN);
    }

    @Test
    void shouldBeUnconfiguredWithoutToken() {
        assertThat(new MonobankProperties(BASE_URL, null, null, CONNECT, READ).isConfigured()).isFalse();
        assertThat(new MonobankProperties(BASE_URL, "", "", CONNECT, READ).webhookUrl()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api.monobank.ua", "ftp://api.monobank.ua"})
    void shouldRefuseBaseUrlThatIsNoHttpUrl(String baseUrl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MonobankProperties(baseUrl, TOKEN, "", CONNECT, READ))
                .withMessageContaining("base-url");
    }

    @Test
    void shouldRefuseTokenWithSpaceInIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MonobankProperties(BASE_URL, "test monobank", "", CONNECT, READ))
                .withMessageContaining("token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gateway.example.com/providers/monobank/webhooks", "ftp://gateway.example.com/hooks",
            "https:///providers/monobank/webhooks"})
    void shouldRefuseWebhookUrlThatIsNoHttpUrl(String webhookUrl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MonobankProperties(BASE_URL, TOKEN, webhookUrl, CONNECT, READ))
                .withMessageContaining("webhook-url");
    }

    @Test
    void shouldNeedReadTimeoutLongerThanConnectTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MonobankProperties(BASE_URL, TOKEN, "", CONNECT, CONNECT))
                .withMessageContaining("read-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MonobankProperties(BASE_URL, TOKEN, "", Duration.ZERO, READ))
                .withMessageContaining("connect-timeout");
    }

    @Test
    void shouldNotShowTokenInText() {
        assertThat(new MonobankProperties(BASE_URL, TOKEN, WEBHOOK_URL, CONNECT, READ).toString())
                .doesNotContain(TOKEN)
                .contains(BASE_URL, WEBHOOK_URL);
    }
}
