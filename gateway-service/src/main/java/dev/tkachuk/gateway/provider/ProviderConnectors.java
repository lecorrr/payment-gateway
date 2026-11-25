package dev.tkachuk.gateway.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every connector by its name, and the ones new payments go to, in gateway.providers.order. A payment is looked up
 * with the connector that charged it, whatever the order is now, so changing it leaves the payments in flight to
 * their own providers.
 */
@Component
public class ProviderConnectors {

    private static final Logger log = LoggerFactory.getLogger(ProviderConnectors.class);

    private final Map<String, ProviderConnector> byName = new TreeMap<>();
    private final List<ProviderConnector> ordered;

    public ProviderConnectors(List<ProviderConnector> connectors, ProvidersProperties properties) {
        for (ProviderConnector connector : connectors) {
            if (byName.putIfAbsent(connector.name(), connector) != null) {
                throw new IllegalStateException("Two connectors are named " + connector.name());
            }
        }
        List<ProviderConnector> chosen = new ArrayList<>();
        for (String name : properties.order()) {
            ProviderConnector connector = byName.get(name);
            if (connector == null) {
                throw new IllegalArgumentException("gateway.providers.order names " + name
                        + ", but the gateway has connectors for " + String.join(", ", byName.keySet()) + " only");
            }
            List<String> missing = connector.missingSettings();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("gateway.providers.order names " + name + ", which needs "
                        + String.join(", ", missing));
            }
            chosen.add(connector);
        }
        this.ordered = List.copyOf(chosen);
        log.info("New payments go to {}", String.join(", then ", properties.order()));
    }

    /** The providers new payments go to, the preferred one first. */
    public List<ProviderConnector> ordered() {
        return ordered;
    }

    public Optional<ProviderConnector> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
