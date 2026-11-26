package dev.tkachuk.gateway.provider.acquirersim;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tkachuk.gateway.provider.RefundRequest;

/** The body of acquirer-sim's POST /v1/refunds, in its own (snake_case) names. */
record SimRefundRequest(@JsonProperty("charge_id") String chargeId, String reference, long amount) {

    static SimRefundRequest from(RefundRequest request) {
        return new SimRefundRequest(request.chargeReference(), request.reference().toString(), request.amount());
    }
}
