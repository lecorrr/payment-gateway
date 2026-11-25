package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderConnector;
import dev.tkachuk.gateway.provider.ProviderResult;
import dev.tkachuk.gateway.provider.ProviderResult.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Card payments: one charge per Idempotency-Key, and a background check for charges whose outcome got lost. The
 * provider is called outside any transaction; the payment is stored as PROCESSING before the call, so a crash
 * in between leaves a payment the checker finds, never a charge the gateway doesn't know about.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RequestFingerprints fingerprints;
    private final ProviderConnector provider;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentService(PaymentRepository payments, RequestFingerprints fingerprints, ProviderConnector provider,
            PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.fingerprints = fingerprints;
        this.provider = provider;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A repeat of an earlier request with the same Idempotency-Key gets the payment that request made, as it is now;
     * the same key with a different request is refused.
     */
    public PaymentResult create(UUID merchantId, String idempotencyKey, NewPayment request) {
        String fingerprint = fingerprints.of(request);
        Optional<Payment> earlier = payments.findByIdempotencyKey(merchantId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), fingerprint);
        }

        Instant now = now();
        CardExpiry expiry = request.card().expiry();
        if (expiry.isExpiredOn(LocalDate.ofInstant(now, ZoneOffset.UTC))) {
            throw new GatewayException(ErrorCode.CARD_EXPIRED, "The card expired at the end of " + expiry);
        }
        Payment payment = newPayment(merchantId, idempotencyKey, fingerprint, request, now);
        if (!payments.insertIfAbsent(payment)) {
            // A request with the same key was stored between the lookup above and this insert.
            Payment first = payments.findByIdempotencyKey(merchantId, idempotencyKey).orElseThrow(
                    () -> new IllegalStateException("Idempotency-Key " + idempotencyKey + " is taken, but not found"));
            return replay(first, fingerprint);
        }
        log.info("Payment {} for order {} created, charging it with {}", payment.id(), payment.orderId(),
                provider.name());

        ProviderResult result = provider.charge(new ChargeRequest(payment.id(), payment.amount(), payment.currency(),
                request.card(), request.description()));
        Payment recorded = record(payment, result);
        if (!recorded.status().isFinal()) {
            log.info("Payment {} stays PROCESSING: {} gave no outcome; first check at {}", payment.id(),
                    provider.name(), payment.nextCheckAt());
        }
        return new PaymentResult(recorded, false);
    }

    public Payment get(UUID merchantId, UUID paymentId) {
        return payments.find(merchantId, paymentId)
                .orElseThrow(() -> new GatewayException(ErrorCode.PAYMENT_NOT_FOUND, "No payment " + paymentId));
    }

    /**
     * Looks up the PROCESSING payments that are due. One the provider still has no record of when give-up-after has
     * passed becomes FAILED; while the provider can't be asked, a payment stays PROCESSING. Returns how many payments
     * were looked up.
     */
    public int checkPending() {
        Instant now = now();
        List<Payment> due = payments.claimDue(now, now.plus(properties.checkInterval()), properties.checkBatchSize());
        for (Payment payment : due) {
            ProviderResult result = provider.lookup(payment.id());
            boolean givingUp = result.outcome() == Outcome.NOT_FOUND
                    && !now.isBefore(payment.createdAt().plus(properties.giveUpAfter()));
            if (givingUp) {
                finish(payment, PaymentStatus.FAILED, null, FailureCodes.PROVIDER_NO_RECORD);
            } else {
                record(payment, result);
            }
        }
        return due.size();
    }

    private Payment newPayment(UUID merchantId, String idempotencyKey, String fingerprint, NewPayment request,
            Instant now) {
        CardNumber number = request.card().number();
        return new Payment(UUID.randomUUID(), merchantId, idempotencyKey, fingerprint, request.orderId(),
                request.amount(), request.currency(), request.description(), PaymentStatus.PROCESSING,
                number.brand(), number.bin(), number.last4(), provider.name(), null, null,
                now.plus(properties.firstCheckAfter()), now, now);
    }

    private static PaymentResult replay(Payment earlier, String fingerprint) {
        if (!earlier.requestFingerprint().equals(fingerprint)) {
            throw new GatewayException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was sent with a different payment request; use a new key for a new payment");
        }
        return new PaymentResult(earlier, true);
    }

    // Without an outcome (NOT_FOUND, UNKNOWN) the payment stays PROCESSING until its next check.
    private Payment record(Payment payment, ProviderResult result) {
        return switch (result.outcome()) {
            case APPROVED -> finish(payment, PaymentStatus.SUCCEEDED, result.providerReference(), null);
            case DECLINED -> finish(payment, PaymentStatus.DECLINED, result.providerReference(), result.code());
            case FAILED -> finish(payment, PaymentStatus.FAILED, result.providerReference(), result.code());
            case NOT_FOUND, UNKNOWN -> payment;
        };
    }

    private Payment finish(Payment payment, PaymentStatus status, @Nullable String providerReference,
            @Nullable String code) {
        Optional<Payment> finished = payments.finish(payment.id(), status, providerReference, code, now());
        if (finished.isEmpty()) {
            // The request or the checker recorded an outcome first; that one stands.
            return payments.find(payment.merchantId(), payment.id())
                    .orElseThrow(() -> new IllegalStateException("Payment " + payment.id() + " is gone"));
        }
        log.info("Payment {} {}{}", payment.id(), status, code == null ? "" : " (" + code + ")");
        return finished.get();
    }

    // PostgreSQL keeps microseconds, so the payment returned right away matches the one read back later.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
