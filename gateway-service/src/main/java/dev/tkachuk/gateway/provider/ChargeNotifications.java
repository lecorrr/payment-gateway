package dev.tkachuk.gateway.provider;

import java.util.UUID;

/**
 * Where a provider's webhook reports that one of its charges changed. The report is only a reason to ask: the
 * payment is looked up with the provider again, as when the customer comes back from a challenge, so a report that
 * arrives late or out of order can't set a payment back.
 */
public interface ChargeNotifications {

    /** The provider named so says the charge with this reference (a payment's id) changed. */
    void chargeChanged(String provider, UUID reference);
}
