package dev.tkachuk.gateway.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.webhooks.
 *
 * @param dispatchInterval how often the outbox is looked at
 * @param batchSize        events tried per run
 * @param connectTimeout   for connecting to a merchant's endpoint
 * @param readTimeout      for its answer; Standard Webhooks suggests 15 to 30 seconds
 * @param retryDelays      the wait before each retry of an event not delivered, in turn; after the last one it is
 *                         given up
 */
@ConfigurationProperties("gateway.webhooks")
public record WebhookProperties(Duration dispatchInterval, int batchSize, Duration connectTimeout,
        Duration readTimeout, List<Duration> retryDelays) {

    private static final String PREFIX = "gateway.webhooks.";
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

    public WebhookProperties {
        requirePositive(dispatchInterval, "dispatch-interval");
        if (batchSize < 1) {
            throw new IllegalArgumentException(PREFIX + "batch-size must be at least 1, got " + batchSize);
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        if (retryDelays == null) {
            throw new IllegalArgumentException(PREFIX + "retry-delays is missing; an empty list means no retries");
        }
        retryDelays.forEach(delay -> requirePositive(delay, "retry-delays"));
        retryDelays = List.copyOf(retryDelays);
    }

    /**
     * How long claimed events stay with the instance that claimed them: long enough to try the whole batch with
     * every endpoint at its timeouts, so no other instance sends them meanwhile.
     */
    Duration lease() {
        return connectTimeout.plus(readTimeout).multipliedBy(batchSize).plus(LEASE_MARGIN);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(PREFIX + name + " must be positive, got " + value);
        }
    }
}
