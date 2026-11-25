package dev.tkachuk.gateway.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class DemoMerchantPropertiesTest {

    private static final String KEY = "test-demo-merchant-key-0123456789";

    @ParameterizedTest
    @NullAndEmptySource
    void shouldMeanNoDemoMerchantWithoutKey(String apiKey) {
        assertThat(new DemoMerchantProperties("demo-shop", "Demo Shop", apiKey).isConfigured()).isFalse();
    }

    @Test
    void shouldRejectShortKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoMerchantProperties("demo-shop", "Demo Shop", "too-short"))
                .withMessageContaining("api-key");
    }

    @Test
    void shouldNeedCodeAndNameWithKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DemoMerchantProperties(" ", "Demo Shop", KEY));
        assertThatIllegalArgumentException().isThrownBy(() -> new DemoMerchantProperties("demo-shop", null, KEY));
    }

    @Test
    void shouldHideKey() {
        DemoMerchantProperties properties = new DemoMerchantProperties("demo-shop", "Demo Shop", KEY);

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.toString()).contains("demo-shop").doesNotContain(KEY);
    }
}
