package dev.tkachuk.gateway.provider;

import java.util.UUID;

/**
 * Where a provider's webhook reports that something about a charge's refunds changed. As with
 * {@link ChargeNotifications}, the report is only a reason to ask: each refund still waiting is looked up with the
 * provider again.
 */
public interface RefundNotifications {

    /** The provider named so reports a change to the refunds of the charge with this reference (a payment's id). */
    void refundsChanged(String provider, UUID paymentReference);
}
