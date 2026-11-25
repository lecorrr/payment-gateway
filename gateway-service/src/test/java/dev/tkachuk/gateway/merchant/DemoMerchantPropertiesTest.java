package dev.tkachuk.gateway.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DemoMerchantPropertiesTest {

    private static final String KEY = "test-demo-merchant-key-0123456789";
    private static final String WEBHOOK_URL = "http://merchant-sim:8092/webhooks";
    private static final String WEBHOOK_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    @ParameterizedTest
    @NullAndEmptySource
    void shouldMeanNoDemoMerchantWithoutKey(String apiKey) {
        assertThat(new DemoMerchantProperties("demo-shop", "Demo Shop", apiKey, null, null).isConfigured()).isFalse();
    }

    @Test
    void shouldRejectShortKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", "Demo Shop", "too-short", null, null))
                .withMessageContaining("api-key");
    }

    @Test
    void shouldNeedCodeAndNameWithKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties(" ", "Demo Shop", KEY, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", null, KEY, null, null));
    }

    @Test
    void shouldTakeWebhookEndpoint() {
        DemoMerchantProperties properties = new DemoMerchantProperties("demo-shop", "Demo Shop", KEY, WEBHOOK_URL,
                WEBHOOK_SECRET);

        assertThat(properties.hasWebhook()).isTrue();
        assertThat(new DemoMerchantProperties("demo-shop", "Demo Shop", KEY, "", "").hasWebhook()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "http://merchant-sim:8092/webhooks, NULL",
            "NULL, whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"}, nullValues = "NULL")
    void shouldNeedWebhookUrlAndSecretTogether(String webhookUrl, String webhookSecret) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", "Demo Shop", KEY, webhookUrl, webhookSecret))
                .withMessageContaining("go together");
    }

    @Test
    void shouldNeedDemoMerchantForWebhook() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", "Demo Shop", "", WEBHOOK_URL, WEBHOOK_SECRET))
                .withMessageContaining("api-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchant-sim:8092/webhooks", "ftp://merchant-sim/webhooks", "http:///webhooks"})
    void shouldRejectWebhookUrlThatIsNoHttpUrl(String webhookUrl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", "Demo Shop", KEY, webhookUrl, WEBHOOK_SECRET))
                .withMessageContaining("webhook-url");
    }

    @Test
    void shouldRejectSecretOfWrongFormat() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", "Demo Shop", KEY, WEBHOOK_URL,
                        "not-a-whsec-secret"))
                .withMessageContaining("whsec_");
    }

    @Test
    void shouldHideKeyAndSecret() {
        DemoMerchantProperties properties = new DemoMerchantProperties("demo-shop", "Demo Shop", KEY, WEBHOOK_URL,
                WEBHOOK_SECRET);

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.toString()).contains("demo-shop", WEBHOOK_URL).doesNotContain(KEY, WEBHOOK_SECRET);
    }
}
