package dev.tkachuk.gateway.merchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** gateway.demo-merchant: a merchant set up at startup for demos and local runs; none while api-key is empty. */
@ConfigurationProperties("gateway.demo-merchant")
public record DemoMerchantProperties(String code, String name, String apiKey) {

    static final int MIN_API_KEY_LENGTH = 24;

    public DemoMerchantProperties {
        apiKey = apiKey == null ? "" : apiKey;
        if (!apiKey.isEmpty()) {
            if (apiKey.length() < MIN_API_KEY_LENGTH) {
                throw new IllegalArgumentException("gateway.demo-merchant.api-key must have at least "
                        + MIN_API_KEY_LENGTH + " characters");
            }
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("gateway.demo-merchant needs a code and a name with its api-key");
            }
        }
    }

    boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String toString() {
        return "DemoMerchantProperties[code=" + code + ", name=" + name + ", apiKey=***]";
    }
}
