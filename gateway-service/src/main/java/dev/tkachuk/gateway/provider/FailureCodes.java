package dev.tkachuk.gateway.provider;

/**
 * The gateway's own reasons for a FAILED payment or refund; declines carry the provider's codes instead. For a refund
 * the first three mean the same as for a payment, with "charge" read as "refund".
 */
public final class FailureCodes {

    /**
     * The provider could not be reached, or turned the charge away unread (429), so it never got it; and no other
     * provider could take it either.
     */
    public static final String PROVIDER_UNAVAILABLE = "provider_unavailable";

    /** The provider refused the request itself (a 4xx), so it did not charge the card. */
    public static final String PROVIDER_REJECTED_REQUEST = "provider_rejected_request";

    /** The answer to the charge was lost, and the provider still had no record of it when the gateway gave up. */
    public static final String PROVIDER_NO_RECORD = "provider_no_record";

    /** The provider refused the refund without saying why in a code of its own. */
    public static final String REFUND_DECLINED = "refund_declined";

    private FailureCodes() {
    }
}
