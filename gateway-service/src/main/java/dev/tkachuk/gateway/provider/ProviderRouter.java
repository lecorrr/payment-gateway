package dev.tkachuk.gateway.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.StateTransition;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Picks the provider for each new charge: the first in gateway.providers.order whose circuit lets a charge through
 * and that no instance has marked as failing. Each provider has a Resilience4j circuit breaker, which counts what
 * ProviderResult.isProviderFailure says. When a circuit opens, the provider is marked as failing for every instance
 * through SharedCircuits; when it closes, the mark goes. An open circuit lets trial charges through again on the
 * first request after its open time, so there is no timer to keep.
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final ProviderConnectors providers;
    private final SharedCircuits sharedCircuits;
    private final Duration openFor;
    private final Map<String, CircuitBreaker> circuits = new HashMap<>();

    ProviderRouter(ProviderConnectors providers, CircuitBreakerRegistry circuitBreakers, SharedCircuits sharedCircuits,
            ProvidersProperties properties) {
        this.providers = providers;
        this.sharedCircuits = sharedCircuits;
        this.openFor = properties.circuitBreaker().openFor();
        for (ProviderConnector provider : providers.ordered()) {
            CircuitBreaker circuit = circuitBreakers.circuitBreaker(provider.name());
            circuit.getEventPublisher()
                    .onStateTransition(event -> changed(provider.name(), circuit, event.getStateTransition()));
            circuits.put(provider.name(), circuit);
        }
    }

    /**
     * The provider to charge with: the first in order, leaving out those already tried, whose circuit lets a charge
     * through. Empty when none does.
     */
    public Optional<ProviderRoute> next(Set<String> tried) {
        List<String> candidates = providers.ordered().stream()
                .map(ProviderConnector::name)
                .filter(name -> !tried.contains(name))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Set<String> openElsewhere = sharedCircuits.openOnes(candidates);
        for (ProviderConnector provider : providers.ordered()) {
            String name = provider.name();
            if (!candidates.contains(name) || openElsewhere.contains(name)) {
                continue;
            }
            CircuitBreaker circuit = circuits.get(name);
            if (circuit.tryAcquirePermission()) {
                return Optional.of(new ProviderRoute(provider, circuit));
            }
        }
        return Optional.empty();
    }

    private void changed(String provider, CircuitBreaker circuit, StateTransition transition) {
        switch (transition.getToState()) {
            case OPEN -> {
                CircuitBreaker.Metrics metrics = circuit.getMetrics();
                log.warn("{} is failing ({}% of its last {} charges): new payments skip it for {}", provider,
                        Math.round(metrics.getFailureRate()), metrics.getNumberOfBufferedCalls(), readable(openFor));
                sharedCircuits.markOpen(provider, openFor);
            }
            case HALF_OPEN -> log.info("{} gets a few charges again, to see whether it is back", provider);
            case CLOSED -> {
                log.info("{} is back: new payments go to it again", provider);
                sharedCircuits.clear(provider);
            }
            default -> {
                // The other states are set by hand; the gateway never does.
            }
        }
    }

    // PT30S as 30s, as application.yml writes it.
    private static String readable(Duration duration) {
        return duration.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
