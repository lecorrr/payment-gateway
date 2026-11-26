package dev.tkachuk.gateway.provider.monobank;

import dev.tkachuk.gateway.provider.RefundRequest;
import java.util.UUID;

/**
 * The body of monobank's POST /api/merchant/invoice/cancel: the invoice to refund, the merchant's own reference for
 * this refund (extRef), and the amount in minor units, which may be part of what was paid.
 */
record CancelRequest(String invoiceId, String extRef, long amount) {

    static CancelRequest from(RefundRequest request) {
        return new CancelRequest(request.chargeReference(), extRefOf(request.reference()), request.amount());
    }

    /** The refund's id as monobank's examples show an extRef: 32 hex digits. */
    static String extRefOf(UUID refundId) {
        return refundId.toString().replace("-", "");
    }
}
