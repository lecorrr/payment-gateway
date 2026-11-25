package dev.tkachuk.gateway.provider;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.providers: the providers new payments go to, in order of preference, and when a failing one is skipped.
 * Each provider has its own settings below these.
 */
@ConfigurationProperties("gateway.providers")
public record ProvidersProperties(List<String> order, CircuitSettings circuitBreaker) {

    public ProvidersProperties {
        if (order == null || order.isEmpty()) {
            throw new IllegalArgumentException("gateway.providers.order is empty; set GATEWAY_PROVIDERS");
        }
        order = order.stream().map(name -> name == null ? "" : name.strip()).toList();
        Set<String> seen = new HashSet<>();
        for (String name : order) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("gateway.providers.order has an empty name: " + order);
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("gateway.providers.order names " + name + " twice: " + order);
            }
        }
        if (circuitBreaker == null) {
            throw new IllegalArgumentException("gateway.providers.circuit-breaker is missing");
        }
    }

    /**
     * When a provider counts as failing. Over its last windowSize charges, once at least minimumCalls were made, a
     * provider whose share of failures (no answer, or no way through) reaches failureRatePercent is skipped for
     * openFor. Then trialCalls charges go to it again, and decide whether it is back.
     */
    public record CircuitSettings(int windowSize, int minimumCalls, int failureRatePercent, Duration openFor,
            int trialCalls) {

        public CircuitSettings {
            if (windowSize < 1) {
                throw new IllegalArgumentException(
                        "gateway.providers.circuit-breaker.window-size must be at least 1, got " + windowSize);
            }
            if (minimumCalls < 1 || minimumCalls > windowSize) {
                throw new IllegalArgumentException("gateway.providers.circuit-breaker.minimum-calls must be from 1 to "
                        + "window-size (" + windowSize + "), got " + minimumCalls);
            }
            if (failureRatePercent < 1 || failureRatePercent > 100) {
                throw new IllegalArgumentException("gateway.providers.circuit-breaker.failure-rate-percent must be "
                        + "from 1 to 100, got " + failureRatePercent);
            }
            if (openFor == null || openFor.isNegative() || openFor.isZero()) {
                throw new IllegalArgumentException(
                        "gateway.providers.circuit-breaker.open-for must be positive, got " + openFor);
            }
            if (trialCalls < 1) {
                throw new IllegalArgumentException(
                        "gateway.providers.circuit-breaker.trial-calls must be at least 1, got " + trialCalls);
            }
        }
    }
}
