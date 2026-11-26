package dev.tkachuk.gateway.payment;

import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs the check of PROCESSING refunds every check-interval, one run at a time per instance, as PendingPaymentChecker
 * does for payments.
 */
@Component
class PendingRefundChecker implements SchedulingConfigurer {

    private final RefundService refunds;
    private final PaymentProperties properties;

    PendingRefundChecker(RefundService refunds, PaymentProperties properties) {
        this.refunds = refunds;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(refunds::checkPending, properties.checkInterval());
    }
}
