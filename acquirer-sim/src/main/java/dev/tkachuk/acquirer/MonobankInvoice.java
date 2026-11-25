package dev.tkachuk.acquirer;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A charge as monobank shows an invoice; the invoice id is the charge's id. Statuses: "success", "failure" with an
 * errCode from monobank's list of payment errors and a reason in words, "expired" for a challenge left open, and
 * "processing" while one waits. payment-direct answers without the errCode, as monobank does, and with tdsUrl for a
 * challenge.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record MonobankInvoice(
        String invoiceId,
        String status,
        String errCode,
        String failureReason,
        long amount,
        int ccy,
        long finalAmount,
        Instant createdDate,
        String reference,
        String tdsUrl) {

    private static final Map<String, Integer> CCY_BY_CURRENCY = Map.of("UAH", 980, "USD", 840, "EUR", 978);

    private record Failure(String errCode, String reason) {
    }

    // The card's bank refusing for a reason of its own; monobank lists it as errCode 6.
    private static final Failure DECLINED = new Failure("6", "The card's bank declined the payment");

    private static final Map<String, Failure> FAILURES = Map.of(
            "do_not_honor", DECLINED,
            "insufficient_funds", new Failure("59", "Not enough money on the card"),
            "incorrect_cvc", new Failure("80", "Wrong CVV"),
            "expired_card", new Failure("51", "The card has expired"),
            "processing_error", new Failure("1000", "Something went wrong at the bank"),
            Issuer.AUTHENTICATION_FAILED, new Failure("1054", "3-D Secure was not passed"));

    /** The invoice as monobank's status and webhooks show it. */
    static MonobankInvoice of(Charge charge) {
        String status = statusOf(charge);
        Failure failure = status.equals("failure")
                ? FAILURES.getOrDefault(charge.declineCode(), DECLINED)
                : null;
        return new MonobankInvoice(charge.id(), status, failure == null ? null : failure.errCode(),
                failure == null ? null : failure.reason(), charge.amount(), ccyOf(charge.currency()),
                status.equals("success") ? charge.amount() : 0, charge.createdAt(), charge.reference(), null);
    }

    /** The invoice as payment-direct answers: the reason for a failure in words only, and the challenge's URL. */
    static MonobankInvoice paymentDirectAnswer(Charge charge) {
        MonobankInvoice invoice = of(charge);
        return new MonobankInvoice(invoice.invoiceId, invoice.status, null, invoice.failureReason, invoice.amount,
                invoice.ccy, invoice.finalAmount, invoice.createdDate, invoice.reference, charge.actionUrl());
    }

    /** The currency of an ISO 4217 number, among those acquirer-sim takes. */
    static Optional<String> currencyOf(int ccy) {
        return CCY_BY_CURRENCY.entrySet().stream()
                .filter(entry -> entry.getValue() == ccy)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    // /v1/charges takes any three letters, so a charge made there may have a currency without a number here: 0.
    private static int ccyOf(String currency) {
        return CCY_BY_CURRENCY.getOrDefault(currency, 0);
    }

    private static String statusOf(Charge charge) {
        return switch (charge.status()) {
            case Charge.APPROVED -> "success";
            case Charge.REQUIRES_ACTION -> "processing";
            default -> Issuer.AUTHENTICATION_EXPIRED.equals(charge.declineCode()) ? "expired" : "failure";
        };
    }
}
