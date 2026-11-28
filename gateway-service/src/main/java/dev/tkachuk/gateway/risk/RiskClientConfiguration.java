package dev.tkachuk.gateway.risk;

import dev.tkachuk.risk.api.RiskServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetricsPublisher;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** The client of risk-service, and the circuit breaker in front of it. */
@Configuration(proxyBeanMethods = false)
class RiskClientConfiguration {

    // risk-service counts as failing once half of its last ten answers, at least five of them, were errors or late.
    private static final int WINDOW_SIZE = 10;
    private static final int MINIMUM_CALLS = 5;
    private static final int FAILURE_RATE_PERCENT = 50;
    private static final int TRIAL_CALLS = 2;

    /**
     * The channel named risk, which goes where spring.grpc.client.channel.risk.target says: one for the risk checks
     * and for RiskWarmUp, which connects it before the gateway reports ready. The factory that made it shuts it down.
     */
    @Bean(destroyMethod = "")
    ManagedChannel riskChannel(GrpcChannelFactory channels) {
        return channels.createChannel("risk");
    }

    @Bean
    RiskServiceGrpc.RiskServiceBlockingStub riskServiceStub(ManagedChannel riskChannel) {
        return RiskServiceGrpc.newBlockingStub(riskChannel);
    }

    /** Made through a registry of its own, which publishes its state as resilience4j.circuitbreaker.* metrics. */
    @Bean
    CircuitBreaker riskServiceCircuit(RiskProperties properties, Clock clock, MeterRegistry meters) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(FAILURE_RATE_PERCENT)
                .waitDurationInOpenState(properties.openFor())
                .permittedNumberOfCallsInHalfOpenState(TRIAL_CALLS)
                .maxWaitDurationInHalfOpenState(properties.openFor())
                .clock(clock)
                .build();
        return CircuitBreakerRegistry.of(config, new TaggedCircuitBreakerMetricsPublisher(meters))
                .circuitBreaker("risk-service");
    }
}
