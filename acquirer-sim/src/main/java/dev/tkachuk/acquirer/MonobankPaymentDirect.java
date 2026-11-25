package dev.tkachuk.acquirer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * POST /api/merchant/invoice/payment-direct, in monobank's names: a debit of the card, whose amount is in minor units
 * and whose currency is its ISO 4217 number. The reference is the caller's id for the charge, as in /v1/charges.
 */
record MonobankPaymentDirect(
        @NotNull @Positive Long amount,
        @NotNull Integer ccy,
        @NotNull @Valid CardData cardData,
        @NotNull @Valid MerchantPaymInfo merchantPaymInfo,
        @NotBlank @Size(max = 2048) @Pattern(regexp = "https?://\\S+") String redirectUrl,
        @Size(max = 2048) @Pattern(regexp = "https?://\\S+") String webHookUrl,
        @Pattern(regexp = "debit", message = "only debit is simulated") String paymentType,
        @Pattern(regexp = "client|merchant") String initiationKind) {

    /** The card number, its expiry as mmyy, and the CVV. */
    record CardData(
            @NotNull @Pattern(regexp = "[0-9]{12,19}") String pan,
            @NotNull @Pattern(regexp = "(0[1-9]|1[0-2])[0-9]{2}") String exp,
            @NotNull @Pattern(regexp = "[0-9]{3,4}") String cvv) {

        @Override
        public String toString() {
            return "CardData[***]";
        }
    }

    record MerchantPaymInfo(@NotBlank @Size(max = 64) String reference, @Size(max = 280) String destination) {
    }

    /** The same charge in acquirer-sim's own terms, so Issuer decides it as any other; exp is valid by now. */
    ChargeRequest toChargeRequest(String currency) {
        int month = Integer.parseInt(cardData.exp().substring(0, 2));
        int year = 2000 + Integer.parseInt(cardData.exp().substring(2));
        ChargeRequest.Card card = new ChargeRequest.Card(cardData.pan(), month, year, cardData.cvv(), null);
        return new ChargeRequest(merchantPaymInfo.reference(), amount, currency, card, merchantPaymInfo.destination(),
                redirectUrl);
    }
}
