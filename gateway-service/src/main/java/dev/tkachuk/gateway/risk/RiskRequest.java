package dev.tkachuk.gateway.risk;

import dev.tkachuk.gateway.common.Currency;
import java.util.UUID;

/**
 * What risk-service is told about a new payment: no card number, only a keyed hash of it (the card token) and its
 * first six digits. attemptId is the same for every try of one payment request.
 */
public record RiskRequest(String attemptId, UUID paymentId, UUID merchantId, long amount, Currency currency,
        String cardToken, String cardBin) {

    @Override
    public String toString() {
        return "RiskRequest[attemptId=" + attemptId + ", paymentId=" + paymentId + ", merchantId=" + merchantId
                + ", amount=" + amount + ", currency=" + currency + "]";
    }
}
