package dev.tkachuk.gateway.provider.monobank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * The parts of a monobank invoice the gateway reads, as payment-direct, the invoice status and a webhook all send
 * them; fields it doesn't know are ignored. errCode says why a payment failed, failureReason the same in words, and
 * tdsUrl, from payment-direct only, is where the customer passes 3-D Secure. The reference is the payment's id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Invoice(
        @Nullable String invoiceId,
        @Nullable String status,
        @Nullable String errCode,
        @Nullable String failureReason,
        @Nullable String tdsUrl,
        @Nullable String reference) {

    Invoice withErrCode(@Nullable String code) {
        return new Invoice(invoiceId, status, code, failureReason, tdsUrl, reference);
    }
}
