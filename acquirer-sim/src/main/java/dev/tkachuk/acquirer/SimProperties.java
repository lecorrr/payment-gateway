package dev.tkachuk.acquirer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sim.
 *
 * @param apiKey     the Bearer token every /v1 request needs
 * @param slowDelay  how long the slow test card waits before answering
 * @param maxCharges charges kept in memory; the oldest are forgotten beyond this
 */
@ConfigurationProperties("sim")
public record SimProperties(String apiKey, Duration slowDelay, int maxCharges) {

    static final int MIN_API_KEY_LENGTH = 16;

    public SimProperties {
        if (apiKey == null || apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalArgumentException("sim.api-key must have at least " + MIN_API_KEY_LENGTH
                    + " characters; set ACQUIRER_SIM_API_KEY");
        }
        if (slowDelay == null || slowDelay.isNegative()) {
            throw new IllegalArgumentException("sim.slow-delay must not be negative, got " + slowDelay);
        }
        if (maxCharges < 1) {
            throw new IllegalArgumentException("sim.max-charges must be at least 1, got " + maxCharges);
        }
    }

    @Override
    public String toString() {
        return "SimProperties[apiKey=***, slowDelay=" + slowDelay + ", maxCharges=" + maxCharges + "]";
    }
}
