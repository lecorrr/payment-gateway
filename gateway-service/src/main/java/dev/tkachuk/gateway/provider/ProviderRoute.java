package dev.tkachuk.gateway.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * One provider picked for one charge, holding a permit of its circuit: either charge with it, or release it unused.
 * What comes of the charge counts toward the circuit.
 */
public final class ProviderRoute {

    private final ProviderConnector provider;
    private final CircuitBreaker circuit;

    ProviderRoute(ProviderConnector provider, CircuitBreaker circuit) {
        this.provider = provider;
        this.circuit = circuit;
    }

    public ProviderConnector provider() {
        return provider;
    }

    public ProviderResult charge(ChargeRequest request) {
        long start = circuit.getCurrentTimestamp();
        ProviderResult result;
        try {
            result = provider.charge(request);
        } catch (RuntimeException e) {
            circuit.onError(circuit.getCurrentTimestamp() - start, circuit.getTimestampUnit(), e);
            throw e;
        }
        circuit.onResult(circuit.getCurrentTimestamp() - start, circuit.getTimestampUnit(), result);
        return result;
    }

    /** The charge went elsewhere after all; the permit goes back to the circuit. */
    public void release() {
        circuit.releasePermission();
    }
}
