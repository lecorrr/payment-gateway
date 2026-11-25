package dev.tkachuk.gateway.provider.acquirersim;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.providers.acquirer-sim. The read timeout must be the longer one: after a connect timeout the request
 * never left, so the card was not charged; after a read timeout it may have been.
 */
@ConfigurationProperties("gateway.providers.acquirer-sim")
public record AcquirerSimProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {

    private static final String PREFIX = "gateway.providers.acquirer-sim.";

    public AcquirerSimProperties {
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException(PREFIX + "base-url must be an http(s) URL, got " + baseUrl);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(PREFIX + "api-key is empty; set ACQUIRER_SIM_API_KEY");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException(PREFIX + "connect-timeout must be positive, got " + connectTimeout);
        }
        if (readTimeout == null || readTimeout.compareTo(connectTimeout) <= 0) {
            throw new IllegalArgumentException(PREFIX + "read-timeout must be longer than connect-timeout ("
                    + connectTimeout + "), got " + readTimeout);
        }
    }

    @Override
    public String toString() {
        return "AcquirerSimProperties[baseUrl=" + baseUrl + ", apiKey=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
