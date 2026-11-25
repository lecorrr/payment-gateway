package dev.tkachuk.gateway.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.payments.
 *
 * @param fingerprintKey         secret key of the request fingerprints (HMAC-SHA256)
 * @param publicUrl              the gateway as customers' browsers reach it; the provider sends the browser back
 *                               to it after a 3-D Secure challenge
 * @param firstCheckAfter        when a payment the provider gave no outcome for is first looked up; longer than any
 *                               provider's read timeout, so the lookup doesn't race the charge
 * @param checkInterval          how often the checker runs, and how long until a payment is looked up again
 * @param challengeCheckInterval how long until a payment waiting for its 3-D Secure challenge is looked up again;
 *                               the customer's return settles it sooner, this is for challenges left unfinished
 * @param giveUpAfter            a payment the provider still has no record of this long after it was created is FAILED
 * @param checkBatchSize         payments looked up per run
 */
@ConfigurationProperties("gateway.payments")
public record PaymentProperties(String fingerprintKey, String publicUrl, Duration firstCheckAfter,
        Duration checkInterval, Duration challengeCheckInterval, Duration giveUpAfter, int checkBatchSize) {

    static final int MIN_FINGERPRINT_KEY_LENGTH = 32;

    private static final String PREFIX = "gateway.payments.";

    public PaymentProperties {
        if (fingerprintKey == null || fingerprintKey.length() < MIN_FINGERPRINT_KEY_LENGTH) {
            throw new IllegalArgumentException(PREFIX + "fingerprint-key must have at least "
                    + MIN_FINGERPRINT_KEY_LENGTH + " characters; set GATEWAY_FINGERPRINT_KEY");
        }
        if (publicUrl == null || !(publicUrl.startsWith("http://") || publicUrl.startsWith("https://"))) {
            throw new IllegalArgumentException(PREFIX + "public-url must be an http(s) URL, got " + publicUrl
                    + "; set GATEWAY_PUBLIC_URL");
        }
        publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        requirePositive(firstCheckAfter, "first-check-after");
        requirePositive(checkInterval, "check-interval");
        requirePositive(challengeCheckInterval, "challenge-check-interval");
        requirePositive(giveUpAfter, "give-up-after");
        if (checkBatchSize < 1) {
            throw new IllegalArgumentException(PREFIX + "check-batch-size must be at least 1, got " + checkBatchSize);
        }
    }

    @Override
    public String toString() {
        return "PaymentProperties[fingerprintKey=***, publicUrl=" + publicUrl + ", firstCheckAfter=" + firstCheckAfter
                + ", checkInterval=" + checkInterval + ", challengeCheckInterval=" + challengeCheckInterval
                + ", giveUpAfter=" + giveUpAfter + ", checkBatchSize=" + checkBatchSize + "]";
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(PREFIX + name + " must be positive, got " + value);
        }
    }
}
