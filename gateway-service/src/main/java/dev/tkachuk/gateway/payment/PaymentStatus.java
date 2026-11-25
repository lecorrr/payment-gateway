package dev.tkachuk.gateway.payment;

/**
 * A payment starts PROCESSING, may wait in REQUIRES_ACTION for the customer's 3-D Secure challenge, and moves once
 * to one of the last three.
 */
public enum PaymentStatus {
    /** Sent to the provider; the outcome is not known yet. */
    PROCESSING,
    /** The card's bank wants the customer to pass a 3-D Secure challenge before it decides. */
    REQUIRES_ACTION,
    /** The card was charged. */
    SUCCEEDED,
    /** The provider or the card's bank refused the charge. */
    DECLINED,
    /** The card was not charged, for a reason on the gateway's or the provider's side. */
    FAILED;

    public boolean isFinal() {
        return this != PROCESSING && this != REQUIRES_ACTION;
    }
}
