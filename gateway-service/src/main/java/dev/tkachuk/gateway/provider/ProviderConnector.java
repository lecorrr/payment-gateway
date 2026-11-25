package dev.tkachuk.gateway.provider;

import java.util.List;

/**
 * One payment provider (an acquirer, a PSP) behind the gateway. A connector translates between the provider's API
 * and these types, quirks included, and never throws for the provider's sake: whatever happens on the wire ends up
 * as a ProviderResult.
 */
public interface ProviderConnector {

    /** Stored with each payment, such as "acquirer-sim". */
    String name();

    /**
     * Charges the card. The reference is the payment's id and goes to the provider with the charge, so a charge
     * whose answer was lost can still be found.
     */
    ProviderResult charge(ChargeRequest request);

    /** What the provider knows about the charge now. */
    ProviderResult lookup(ChargeLookup lookup);

    /**
     * The settings this connector still needs before it can charge, by name; empty when it has them all. The
     * provider new payments go to must have them all.
     */
    default List<String> missingSettings() {
        return List.of();
    }
}
