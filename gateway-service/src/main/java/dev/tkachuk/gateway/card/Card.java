package dev.tkachuk.gateway.card;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The card a payment is made with, as the merchant sent it. It lives for one request: only the brand, the first six
 * and the last four digits are stored. toString() leaves out the CVC and the holder's name.
 */
public record Card(CardNumber number, CardExpiry expiry, Cvc cvc, @Nullable String holder) {

    public Card {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(cvc, "cvc");
    }

    @Override
    public String toString() {
        return "Card[" + number + ", expires " + expiry + "]";
    }
}
