package dev.tkachuk.risk;

import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/**
 * The rules, in order: a blocked card range, an amount over its currency's limit, then too many payments with the
 * card lately. Every payment is counted for its card, declined ones too: a card that keeps trying is what the count
 * is for. When the count can't be had, that rule is left out and the others still apply.
 */
@Component
class RiskRules {

    private final RiskProperties properties;
    private final VelocityCounter velocity;

    RiskRules(RiskProperties properties, VelocityCounter velocity) {
        this.properties = properties;
        this.velocity = velocity;
    }

    Assessment assess(PaymentAttempt attempt) {
        OptionalLong cardPayments = velocity.count(attempt.cardToken(), attempt.attemptId(),
                properties.velocity().window());
        if (properties.blockedBins().contains(attempt.cardBin())) {
            return Assessment.decline(Assessment.BLOCKED_CARD_RANGE);
        }
        Long limit = properties.amountLimits().get(attempt.currency());
        if (limit != null && attempt.amount() > limit) {
            return Assessment.decline(Assessment.AMOUNT_OVER_LIMIT);
        }
        if (cardPayments.isPresent() && cardPayments.getAsLong() > properties.velocity().maxPayments()) {
            return Assessment.decline(Assessment.TOO_MANY_PAYMENTS);
        }
        return Assessment.allow();
    }
}
