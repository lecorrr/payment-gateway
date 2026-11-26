package dev.tkachuk.risk;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * risk: the rules a payment must pass. A card may make velocity.maxPayments payments within velocity.window, the
 * one asked about included; a payment may be no more than its currency's amount limit, in minor units (a currency
 * without one has no limit); and no payment is allowed from a card whose first six digits are in blockedBins.
 */
@ConfigurationProperties("risk")
public record RiskProperties(Velocity velocity, Map<String, Long> amountLimits, Set<String> blockedBins) {

    public RiskProperties {
        if (velocity == null) {
            throw new IllegalArgumentException("risk.velocity is missing");
        }
        Map<String, Long> given = amountLimits == null ? Map.of() : amountLimits;
        Map<String, Long> limits = new HashMap<>();
        for (Map.Entry<String, Long> limit : given.entrySet()) {
            // Map keys from environment variables come in lower case.
            String currency = limit.getKey().strip().toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(
                        "risk.amount-limits needs ISO 4217 letters as keys, such as UAH, got " + limit.getKey());
            }
            if (limit.getValue() == null || limit.getValue() < 1) {
                throw new IllegalArgumentException(
                        "risk.amount-limits." + currency + " must be at least 1, got " + limit.getValue());
            }
            limits.put(currency, limit.getValue());
        }
        amountLimits = Map.copyOf(limits);
        Set<String> bins = new HashSet<>();
        for (String bin : blockedBins == null ? Set.<String>of() : blockedBins) {
            String digits = bin == null ? "" : bin.strip();
            if (!digits.matches("[0-9]{6}")) {
                throw new IllegalArgumentException("risk.blocked-bins needs the first six digits of card numbers, got "
                        + bin);
            }
            bins.add(digits);
        }
        blockedBins = Set.copyOf(bins);
    }

    /** At most maxPayments payments with one card within window. */
    public record Velocity(int maxPayments, Duration window) {

        public Velocity {
            if (maxPayments < 1) {
                throw new IllegalArgumentException("risk.velocity.max-payments must be at least 1, got " + maxPayments);
            }
            if (window == null || window.toMillis() < 1) {
                throw new IllegalArgumentException("risk.velocity.window must be at least 1ms, got " + window);
            }
        }
    }
}
