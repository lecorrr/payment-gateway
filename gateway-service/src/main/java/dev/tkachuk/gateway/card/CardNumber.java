package dev.tkachuk.gateway.card;

import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;

/**
 * A card number (PAN) that passed the Luhn check. Only {@link #digits()} gives the whole number, for sending it to a
 * payment provider; toString() shows the first six and the last four digits, like a receipt.
 */
public final class CardNumber {

    private static final int MIN_DIGITS = 12;
    private static final int MAX_DIGITS = 19;

    private final String digits;

    private CardNumber(String digits) {
        this.digits = digits;
    }

    /** Spaces are ignored: "4111 1111 1111 1111" is fine. */
    public static CardNumber parse(String input) {
        String digits = input.replace(" ", "");
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS || !isAsciiDigits(digits)) {
            throw new GatewayException(ErrorCode.CARD_INVALID, "The card number must have 12 to 19 digits");
        }
        if (!passesLuhnCheck(digits)) {
            throw new GatewayException(ErrorCode.CARD_INVALID, "The card number is not valid");
        }
        return new CardNumber(digits);
    }

    public String digits() {
        return digits;
    }

    public String bin() {
        return digits.substring(0, 6);
    }

    public String last4() {
        return digits.substring(digits.length() - 4);
    }

    public CardBrand brand() {
        return CardBrand.of(digits);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CardNumber number && digits.equals(number.digits);
    }

    @Override
    public int hashCode() {
        return digits.hashCode();
    }

    @Override
    public String toString() {
        return bin() + "*".repeat(digits.length() - 10) + last4();
    }

    // Character.isDigit would also let through digits of other scripts.
    private static boolean isAsciiDigits(String text) {
        return text.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    // From the last digit leftwards every second digit is doubled, minus 9 when that is over 9;
    // the sum of all digits must then end in 0.
    private static boolean passesLuhnCheck(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
