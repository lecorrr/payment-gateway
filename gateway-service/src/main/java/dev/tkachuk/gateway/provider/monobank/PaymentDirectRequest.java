package dev.tkachuk.gateway.provider.monobank;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.provider.ChargeRequest;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * The body of monobank's POST /api/merchant/invoice/payment-direct, in its own names; fields without a value are left
 * out. The amount is in minor units and the currency is its ISO 4217 number.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record PaymentDirectRequest(
        long amount,
        int ccy,
        CardData cardData,
        MerchantPaymInfo merchantPaymInfo,
        String redirectUrl,
        @Nullable String webHookUrl,
        String paymentType,
        String initiationKind) {

    // A debit rather than a hold, started by the customer, who comes back to redirectUrl after 3-D Secure.
    static PaymentDirectRequest from(ChargeRequest request, @Nullable String webHookUrl) {
        Card card = request.card();
        YearMonth expiry = card.expiry().month();
        CardData cardData = new CardData(card.number().digits(),
                "%02d%02d".formatted(expiry.getMonthValue(), expiry.getYear() % 100), card.cvc().value());
        return new PaymentDirectRequest(request.amount(), request.currency().numericCode(), cardData,
                new MerchantPaymInfo(request.reference().toString(), request.description()),
                request.returnUrl().toString(), webHookUrl, "debit", "client");
    }

    /** The card number, its expiry as mmyy, and the CVV. */
    record CardData(String pan, String exp, String cvv) {

        @Override
        public String toString() {
            return "CardData[***]";
        }
    }

    /** The payment's id as the reference monobank keeps, and the payment's description as its purpose. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MerchantPaymInfo(String reference, @Nullable String destination) {
    }
}
