package dev.tkachuk.acquirer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * POST /api/merchant/invoice/cancel, in monobank's names: the invoice to refund, the merchant's reference for the
 * refund (extRef), and the amount in minor units. Without an amount the whole of what is left is refunded.
 */
record MonobankCancel(
        @NotBlank @Size(max = 64) String invoiceId,
        @Size(max = 64) String extRef,
        @Positive Long amount) {
}
