package dev.tkachuk.gateway.payment;

/** A refund starts PROCESSING and moves once to one of the other two. */
public enum RefundStatus {
    /** Sent to the provider, or about to be; how it ends is not known yet. */
    PROCESSING,
    /** The money went back to the card. */
    SUCCEEDED,
    /** Nothing went back: the provider refused the refund, or never got it. */
    FAILED;

    public boolean isFinal() {
        return this != PROCESSING;
    }
}
