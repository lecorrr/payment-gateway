package dev.tkachuk.gateway.provider.monobank;

import dev.tkachuk.gateway.provider.ProviderHttp;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.providers.monobank. Without a token monobank is not configured, which is fine unless it is the active
 * provider. The webhook URL is where monobank should report invoice changes: this gateway's
 * /providers/monobank/webhooks as monobank can reach it, or empty for none. The read timeout must be the longer one:
 * after a connect timeout the request never left, so the card was not charged; after a read timeout it may have been.
 */
@ConfigurationProperties("gateway.providers.monobank")
public record MonobankProperties(String baseUrl, String token, String webhookUrl, Duration connectTimeout,
        Duration readTimeout) {

    private static final String PREFIX = "gateway.providers.monobank.";

    public MonobankProperties {
        token = token == null ? "" : token.strip();
        webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException(PREFIX + "base-url must be an http(s) URL, got " + baseUrl);
        }
        if (token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(PREFIX + "token has a space in it; check GATEWAY_MONOBANK_TOKEN");
        }
        if (!webhookUrl.isEmpty() && !ProviderHttp.isHttpUrl(webhookUrl)) {
            throw new IllegalArgumentException(PREFIX + "webhook-url must be an http(s) URL with a host, got "
                    + webhookUrl);
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException(PREFIX + "connect-timeout must be positive, got " + connectTimeout);
        }
        if (readTimeout == null || readTimeout.compareTo(connectTimeout) <= 0) {
            throw new IllegalArgumentException(PREFIX + "read-timeout must be longer than connect-timeout ("
                    + connectTimeout + "), got " + readTimeout);
        }
    }

    boolean isConfigured() {
        return !token.isEmpty();
    }

    @Override
    public String toString() {
        return "MonobankProperties[baseUrl=" + baseUrl + ", token=" + (token.isEmpty() ? "" : "***")
                + ", webhookUrl=" + webhookUrl + ", connectTimeout=" + connectTimeout + ", readTimeout="
                + readTimeout + "]";
    }
}
