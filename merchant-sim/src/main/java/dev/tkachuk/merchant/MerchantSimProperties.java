package dev.tkachuk.merchant;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * merchant-sim.
 *
 * @param webhookSecret the whsec_ secret the webhooks are signed with; WebhookVerifier checks its format
 * @param tolerance     how far a webhook's timestamp may be from now; older ones are refused as replays
 * @param failFirst     how many deliveries of each webhook are answered 503 before one is taken, to show retries
 * @param maxEvents     webhooks kept for /events; the oldest are forgotten beyond this
 */
@ConfigurationProperties("merchant-sim")
public record MerchantSimProperties(String webhookSecret, Duration tolerance, int failFirst, int maxEvents) {

    public MerchantSimProperties {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("merchant-sim.webhook-secret is empty; set MERCHANT_SIM_WEBHOOK_SECRET "
                    + "to the gateway's GATEWAY_DEMO_MERCHANT_WEBHOOK_SECRET");
        }
        if (tolerance == null || tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException("merchant-sim.tolerance must be positive, got " + tolerance);
        }
        if (failFirst < 0) {
            throw new IllegalArgumentException("merchant-sim.fail-first must not be negative, got " + failFirst);
        }
        if (maxEvents < 1) {
            throw new IllegalArgumentException("merchant-sim.max-events must be at least 1, got " + maxEvents);
        }
    }

    @Override
    public String toString() {
        return "MerchantSimProperties[webhookSecret=***, tolerance=" + tolerance + ", failFirst=" + failFirst
                + ", maxEvents=" + maxEvents + "]";
    }
}
