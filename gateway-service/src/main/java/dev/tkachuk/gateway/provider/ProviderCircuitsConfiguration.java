package dev.tkachuk.gateway.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The circuit breakers of the providers, all with the settings of gateway.providers.circuit-breaker. */
@Configuration(proxyBeanMethods = false)
class ProviderCircuitsConfiguration {

    @Bean
    CircuitBreakerRegistry providerCircuitBreakers(ProvidersProperties properties, Clock clock) {
        ProvidersProperties.CircuitSettings settings = properties.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openFor())
                .permittedNumberOfCallsInHalfOpenState(settings.trialCalls())
                // Trial charges that never report back don't keep the circuit half open for good.
                .maxWaitDurationInHalfOpenState(settings.openFor())
                // Connectors answer with results rather than exceptions; these are the ones that count as failures.
                .recordResult(result -> result instanceof ProviderResult providerResult
                        && providerResult.isProviderFailure())
                .clock(clock)
                .build();
        return CircuitBreakerRegistry.of(config);
    }
}
