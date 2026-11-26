package dev.tkachuk.gateway.risk;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.risk: whether new payments are checked by risk-service (the gRPC channel spring.grpc.client.channel.risk),
 * how long an answer may take, what a payment gets while risk-service can't answer, and how long risk-service is
 * left alone after it kept failing.
 */
@ConfigurationProperties("gateway.risk")
public record RiskProperties(boolean enabled, Duration deadline, WhenUnavailable whenUnavailable, Duration openFor) {

    /** A payment risk-service can't answer about either goes ahead unchecked, or fails. */
    public enum WhenUnavailable {
        ALLOW,
        DECLINE
    }

    public RiskProperties {
        if (deadline == null || deadline.toMillis() < 1) {
            throw new IllegalArgumentException("gateway.risk.deadline must be at least 1ms, got " + deadline);
        }
        if (whenUnavailable == null) {
            throw new IllegalArgumentException("gateway.risk.when-unavailable must be allow or decline");
        }
        if (openFor == null || openFor.isNegative() || openFor.isZero()) {
            throw new IllegalArgumentException("gateway.risk.open-for must be positive, got " + openFor);
        }
    }
}
