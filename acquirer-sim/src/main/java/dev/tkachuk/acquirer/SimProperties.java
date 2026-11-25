package dev.tkachuk.acquirer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sim.
 *
 * @param apiKey       the Bearer token every /v1 request needs
 * @param publicUrl    acquirer-sim as browsers reach it, for the link to a 3-D Secure challenge page
 * @param slowDelay    how long the slow test card waits before answering
 * @param challengeTtl how long a 3-D Secure challenge stays open; after that its charge is declined
 * @param maxCharges   charges kept in memory; the oldest are forgotten beyond this
 */
@ConfigurationProperties("sim")
public record SimProperties(String apiKey, String publicUrl, Duration slowDelay, Duration challengeTtl,
        int maxCharges) {

    static final int MIN_API_KEY_LENGTH = 16;

    public SimProperties {
        if (apiKey == null || apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalArgumentException("sim.api-key must have at least " + MIN_API_KEY_LENGTH
                    + " characters; set ACQUIRER_SIM_API_KEY");
        }
        if (publicUrl == null || !(publicUrl.startsWith("http://") || publicUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("sim.public-url must be an http(s) URL, got " + publicUrl
                    + "; set SIM_PUBLIC_URL");
        }
        publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        if (slowDelay == null || slowDelay.isNegative()) {
            throw new IllegalArgumentException("sim.slow-delay must not be negative, got " + slowDelay);
        }
        if (challengeTtl == null || challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException("sim.challenge-ttl must be positive, got " + challengeTtl);
        }
        if (maxCharges < 1) {
            throw new IllegalArgumentException("sim.max-charges must be at least 1, got " + maxCharges);
        }
    }

    @Override
    public String toString() {
        return "SimProperties[apiKey=***, publicUrl=" + publicUrl + ", slowDelay=" + slowDelay + ", challengeTtl="
                + challengeTtl + ", maxCharges=" + maxCharges + "]";
    }
}
