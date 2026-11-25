package dev.tkachuk.gateway.provider;

import java.util.UUID;

/**
 * One payment provider (an acquirer, a PSP) behind the gateway. A connector translates between the provider's API
 * and these types, quirks included, and never throws for the provider's sake: whatever happens on the wire ends up
 * as a ProviderResult.
 */
public interface ProviderConnector {

    /** Stored with each payment, such as "acquirer-sim". */
    String name();

    /**
     * Charges the card. The reference is the payment's id: the provider must not charge twice for one reference,
     * so a charge whose answer was lost can be looked up by it.
     */
    ProviderResult charge(ChargeRequest request);

    /** What the provider knows about the charge with this reference. */
    ProviderResult lookup(UUID reference);
}
