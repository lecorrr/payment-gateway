package dev.tkachuk.gateway.payment;

import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs the check of PROCESSING payments every check-interval, one run at a time per instance. An error is logged by
 * the scheduler and the next run goes ahead; the payments of a failed run are claimed again at their next check.
 */
@Component
class PendingPaymentChecker implements SchedulingConfigurer {

    private final PaymentService payments;
    private final PaymentProperties properties;

    PendingPaymentChecker(PaymentService payments, PaymentProperties properties) {
        this.payments = payments;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(payments::checkPending, properties.checkInterval());
    }
}
