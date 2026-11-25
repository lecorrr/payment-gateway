package dev.tkachuk.gateway.provider;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The providers some gateway instance found failing, so every instance skips them, not only the one whose circuit
 * opened. Best effort: while the shared store can't be reached, each instance goes by its own circuits.
 */
interface SharedCircuits {

    /** Those of the providers whose circuit some instance has opened, and which are still to be skipped. */
    Set<String> openOnes(List<String> providers);

    /** The provider is to be skipped for openFor. */
    void markOpen(String provider, Duration openFor);

    /** The provider's circuit has closed again. */
    void clear(String provider);
}
