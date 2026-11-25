package dev.tkachuk.gateway.card;

import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import java.util.regex.Pattern;

/** The card's security code. It goes to the provider and nowhere else: never stored, and toString() hides it. */
public record Cvc(String value) {

    private static final Pattern THREE_OR_FOUR_DIGITS = Pattern.compile("[0-9]{3,4}");

    public Cvc {
        if (value == null || !THREE_OR_FOUR_DIGITS.matcher(value).matches()) {
            throw new GatewayException(ErrorCode.CARD_INVALID, "The CVC must have 3 or 4 digits");
        }
    }

    @Override
    public String toString() {
        return "***";
    }
}
