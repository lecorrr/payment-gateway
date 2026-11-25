package dev.tkachuk.gateway.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every connector by its name, and the one new payments go to. A payment is looked up with the connector that
 * charged it, whichever is active now, so switching providers leaves the payments in flight to their own.
 */
@Component
public class ProviderConnectors {

    private static final Logger log = LoggerFactory.getLogger(ProviderConnectors.class);

    private final Map<String, ProviderConnector> byName = new TreeMap<>();
    private final ProviderConnector active;

    public ProviderConnectors(List<ProviderConnector> connectors, ProvidersProperties properties) {
        for (ProviderConnector connector : connectors) {
            if (byName.putIfAbsent(connector.name(), connector) != null) {
                throw new IllegalStateException("Two connectors are named " + connector.name());
            }
        }
        ProviderConnector chosen = byName.get(properties.active());
        if (chosen == null) {
            throw new IllegalArgumentException("gateway.providers.active is " + properties.active()
                    + ", but the gateway has connectors for " + String.join(", ", byName.keySet()) + " only");
        }
        List<String> missing = chosen.missingSettings();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("gateway.providers.active is " + chosen.name() + ", which needs "
                    + String.join(", ", missing));
        }
        this.active = chosen;
        log.info("New payments are charged with {}", chosen.name());
    }

    public ProviderConnector active() {
        return active;
    }

    public Optional<ProviderConnector> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
