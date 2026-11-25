package dev.tkachuk.gateway.payment;

/** A payment starts PROCESSING and moves once, to one of the other three. */
public enum PaymentStatus {
    /** Sent to the provider; the outcome is not known yet. */
    PROCESSING,
    /** The card was charged. */
    SUCCEEDED,
    /** The provider or the card's bank refused the charge. */
    DECLINED,
    /** The card was not charged, for a reason on the gateway's or the provider's side. */
    FAILED;

    public boolean isFinal() {
        return this != PROCESSING;
    }
}
