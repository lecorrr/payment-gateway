package dev.tkachuk.gateway.merchant;

import dev.tkachuk.gateway.webhook.WebhookSecret;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.demo-merchant: a merchant set up at startup for demos and local runs; none while api-key is empty. With a
 * webhook-url, it gets webhooks there, signed with webhook-secret (whsec_...); the two come together or not at all.
 */
@ConfigurationProperties("gateway.demo-merchant")
public record DemoMerchantProperties(String code, String name, String apiKey, String webhookUrl,
        String webhookSecret) {

    static final int MIN_API_KEY_LENGTH = 24;

    private static final String PREFIX = "gateway.demo-merchant.";

    public DemoMerchantProperties {
        apiKey = apiKey == null ? "" : apiKey;
        webhookUrl = webhookUrl == null ? "" : webhookUrl;
        webhookSecret = webhookSecret == null ? "" : webhookSecret;
        if (!apiKey.isEmpty()) {
            if (apiKey.length() < MIN_API_KEY_LENGTH) {
                throw new IllegalArgumentException(PREFIX + "api-key must have at least " + MIN_API_KEY_LENGTH
                        + " characters");
            }
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("gateway.demo-merchant needs a code and a name with its api-key");
            }
        }
        if (webhookUrl.isEmpty() != webhookSecret.isEmpty()) {
            throw new IllegalArgumentException(PREFIX + "webhook-url and webhook-secret go together: set both "
                    + "GATEWAY_DEMO_MERCHANT_WEBHOOK_URL and GATEWAY_DEMO_MERCHANT_WEBHOOK_SECRET, or neither");
        }
        if (!webhookUrl.isEmpty()) {
            if (apiKey.isEmpty()) {
                throw new IllegalArgumentException(PREFIX + "webhook-url needs a demo merchant: set its api-key too");
            }
            requireHttpUrl(webhookUrl);
            WebhookSecret.parse(webhookSecret);
        }
    }

    boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    boolean hasWebhook() {
        return !webhookUrl.isEmpty();
    }

    @Override
    public String toString() {
        return "DemoMerchantProperties[code=" + code + ", name=" + name + ", apiKey=***, webhookUrl=" + webhookUrl
                + ", webhookSecret=" + (webhookSecret.isEmpty() ? "" : "***") + "]";
    }

    private static void requireHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
                return;
            }
        } catch (URISyntaxException e) {
            // Reported below, the same as a URL of the wrong kind.
        }
        throw new IllegalArgumentException(PREFIX + "webhook-url must be an http(s) URL with a host, got " + url);
    }
}
