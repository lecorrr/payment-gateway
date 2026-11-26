package dev.tkachuk.risk;

import org.jspecify.annotations.Nullable;

/** What the rules make of a payment: allowed, or declined with the reason as a code. */
record Assessment(boolean allowed, @Nullable String reason) {

    static final String BLOCKED_CARD_RANGE = "blocked_card_range";
    static final String AMOUNT_OVER_LIMIT = "amount_over_limit";
    static final String TOO_MANY_PAYMENTS = "too_many_payments";

    static Assessment allow() {
        return new Assessment(true, null);
    }

    static Assessment decline(String reason) {
        return new Assessment(false, reason);
    }
}
