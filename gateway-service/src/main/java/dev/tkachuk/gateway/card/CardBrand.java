package dev.tkachuk.gateway.card;

public enum CardBrand {
    VISA,
    MASTERCARD,
    OTHER;

    /** By the leading digits: 4 is Visa; 51 to 55 and 2221 to 2720 are Mastercard. */
    static CardBrand of(String digits) {
        if (digits.startsWith("4")) {
            return VISA;
        }
        int firstTwo = Integer.parseInt(digits.substring(0, 2));
        int firstFour = Integer.parseInt(digits.substring(0, 4));
        if ((firstTwo >= 51 && firstTwo <= 55) || (firstFour >= 2221 && firstFour <= 2720)) {
            return MASTERCARD;
        }
        return OTHER;
    }
}
