package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.gateway.card.CardBrand;
import dev.tkachuk.gateway.common.Currency;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final PaymentMetrics metrics = new PaymentMetrics(meters);

    @Test
    void shouldCountPaymentsByStatusProviderAndCode() {
        metrics.paymentFinished(payment(PaymentStatus.SUCCEEDED, null));
        metrics.paymentFinished(payment(PaymentStatus.SUCCEEDED, null));
        metrics.paymentFinished(payment(PaymentStatus.DECLINED, "risk_declined"));

        assertThat(count(PaymentMetrics.PAYMENTS, "SUCCEEDED", "none")).isEqualTo(2);
        assertThat(count(PaymentMetrics.PAYMENTS, "DECLINED", "risk_declined")).isEqualTo(1);
    }

    @Test
    void shouldCountRefundsByStatusProviderAndCode() {
        metrics.refundFinished(refund(RefundStatus.SUCCEEDED, null));
        metrics.refundFinished(refund(RefundStatus.FAILED, "refund_declined"));

        assertThat(count(PaymentMetrics.REFUNDS, "SUCCEEDED", "none")).isEqualTo(1);
        assertThat(count(PaymentMetrics.REFUNDS, "FAILED", "refund_declined")).isEqualTo(1);
    }

    private double count(String name, String status, String code) {
        return meters.get(name).tags("status", status, "provider", "monobank", "code", code).counter().count();
    }

    private static Payment payment(PaymentStatus status, String declineCode) {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), "key-1", "f".repeat(64), "ORDER-1001", 15000,
                Currency.UAH, null, null, status, CardBrand.VISA, "411111", "1111", "monobank", "p2_1", declineCode,
                0, null, null, NOW, NOW);
    }

    private static Refund refund(RefundStatus status, String failureCode) {
        return new Refund(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "refund-1", "f".repeat(64), 5000,
                Currency.UAH, status, "monobank", null, failureCode, null, NOW, NOW);
    }
}
