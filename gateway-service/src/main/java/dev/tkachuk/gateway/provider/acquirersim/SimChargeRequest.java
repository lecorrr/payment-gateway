package dev.tkachuk.gateway.provider.acquirersim;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.provider.ChargeRequest;
import org.jspecify.annotations.Nullable;

/** The body of acquirer-sim's POST /v1/charges, in its own (snake_case) names. */
record SimChargeRequest(String reference, long amount, String currency, SimCard card, @Nullable String description) {

    static SimChargeRequest from(ChargeRequest request) {
        Card card = request.card();
        SimCard simCard = new SimCard(card.number().digits(), card.expiry().month().getMonthValue(),
                card.expiry().month().getYear(), card.cvc().value(), card.holder());
        return new SimChargeRequest(request.reference().toString(), request.amount(), request.currency().name(),
                simCard, request.description());
    }

    record SimCard(
            String number,
            @JsonProperty("exp_month") int expMonth,
            @JsonProperty("exp_year") int expYear,
            String cvc,
            @Nullable String holder) {

        @Override
        public String toString() {
            return "SimCard[***]";
        }
    }
}
