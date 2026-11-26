package dev.tkachuk.gateway.provider.monobank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The parts of a monobank invoice the gateway reads, as payment-direct, the invoice status and a webhook all send
 * them; fields it doesn't know are ignored. errCode says why a payment failed, failureReason the same in words, and
 * tdsUrl, from payment-direct only, is where the customer passes 3-D Secure. The reference is the payment's id.
 * cancelList holds the refunds monobank accepted for the invoice, each with the extRef it was sent with.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Invoice(
        @Nullable String invoiceId,
        @Nullable String status,
        @Nullable String errCode,
        @Nullable String failureReason,
        @Nullable String tdsUrl,
        @Nullable String reference,
        @Nullable List<CancelItem> cancelList) {

    Invoice withErrCode(@Nullable String code) {
        return new Invoice(invoiceId, status, code, failureReason, tdsUrl, reference, cancelList);
    }

    /** The refund sent with this extRef, if monobank lists it. */
    Optional<CancelItem> cancelOf(String extRef) {
        if (cancelList == null) {
            return Optional.empty();
        }
        return cancelList.stream()
                .filter(Objects::nonNull)
                .filter(item -> extRef.equals(item.extRef()))
                .findFirst();
    }

    /** A refund of the invoice: "processing", "success" or "failure". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelItem(@Nullable String status, @Nullable Long amount, @Nullable String extRef) {
    }
}
