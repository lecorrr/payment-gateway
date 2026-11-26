package dev.tkachuk.risk;

/**
 * A payment the gateway asks about, checked as it arrives; the field names in the messages are the contract's.
 * attemptId is the same for every try of one payment request, so a retry counts once; cardToken is a keyed hash of
 * the card number, the same for every payment with the card.
 */
record PaymentAttempt(String attemptId, String paymentId, String merchantId, long amount, String currency,
        String cardToken, String cardBin) {

    PaymentAttempt {
        requireText("attempt_id", attemptId, 200);
        requireText("payment_id", paymentId, 64);
        requireText("merchant_id", merchantId, 64);
        requireText("card_token", cardToken, 128);
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be at least 1, got " + amount);
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be three ISO 4217 letters, such as UAH");
        }
        if (cardBin == null || !cardBin.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("card_bin must be the six first digits of the card number");
        }
    }

    private static void requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is longer than " + maxLength + " characters");
        }
    }
}
