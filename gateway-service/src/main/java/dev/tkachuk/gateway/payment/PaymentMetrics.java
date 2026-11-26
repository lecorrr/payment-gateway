package dev.tkachuk.gateway.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Counts the payments and refunds that reach an outcome, by status, provider and code: gateway.payments and
 * gateway.refunds, gateway_payments_total and gateway_refunds_total in Prometheus. Each is counted once, when the
 * outcome is stored. The codes are the decline and failure codes, a short list each, so the series stay few.
 */
@Component
class PaymentMetrics {

    static final String PAYMENTS = "gateway.payments";
    static final String REFUNDS = "gateway.refunds";
    static final String NO_CODE = "none";

    private final MeterRegistry meters;

    PaymentMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    void paymentFinished(Payment payment) {
        Counter.builder(PAYMENTS)
                .description("Payments that reached an outcome")
                .tag("status", payment.status().name())
                .tag("provider", payment.provider())
                .tag("code", codeOf(payment.declineCode()))
                .register(meters)
                .increment();
    }

    void refundFinished(Refund refund) {
        Counter.builder(REFUNDS)
                .description("Refunds that reached an outcome")
                .tag("status", refund.status().name())
                .tag("provider", refund.provider())
                .tag("code", codeOf(refund.failureCode()))
                .register(meters)
                .increment();
    }

    private static String codeOf(@Nullable String code) {
        return code == null ? NO_CODE : code;
    }
}
