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
import dev.tkachuk.gateway.webhook.WebhookOutbox;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Card payments: one charge per Idempotency-Key, 3-D Secure challenges, and a background check for charges whose
 * outcome got lost or whose challenge was left unfinished. The provider is called outside any transaction; the
 * payment is stored as PROCESSING before the call, so a crash in between leaves a payment the checker finds, never
 * a charge the gateway doesn't know about. Every status change after that is stored together with its webhook event.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RequestFingerprints fingerprints;
    private final ProviderConnector provider;
    private final WebhookOutbox webhooks;
    private final TransactionTemplate transactions;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentService(PaymentRepository payments, RequestFingerprints fingerprints, ProviderConnector provider,
            WebhookOutbox webhooks, PlatformTransactionManager transactionManager, PaymentProperties properties,
            Clock clock) {
        this.payments = payments;
        this.fingerprints = fingerprints;
        this.provider = provider;
        this.webhooks = webhooks;
        this.transactions = new TransactionTemplate(transactionManager);
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
                request.card(), request.description(), challengeReturnUrl(payment.id())));
        Payment recorded = record(payment, result);
        if (recorded.status() == PaymentStatus.PROCESSING) {
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
     * The customer's browser is back from the 3-D Secure challenge. Nothing the browser brings is trusted: the
     * provider is asked how the challenge went, so the payment is settled before the customer sees the shop again.
     * Returns the payment as it is now, settled or not.
     */
    public Payment returnFromChallenge(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new GatewayException(ErrorCode.PAYMENT_NOT_FOUND, "No payment " + paymentId));
        if (payment.status() != PaymentStatus.REQUIRES_ACTION) {
            return payment;
        }
        return record(payment, provider.lookup(payment.id()));
    }

    /**
     * Looks up the payments that are due: PROCESSING ones whose outcome was lost, and REQUIRES_ACTION ones whose
     * challenge may have been finished or left to expire. One the provider still has no record of when give-up-after
     * has passed becomes FAILED; while the provider can't be asked, a payment stays as it is. Returns how many
     * payments were looked up.
     */
    public int checkPending() {
        Instant now = now();
        List<Payment> due = payments.claimDue(now, now.plus(properties.checkInterval()),
                now.plus(properties.challengeCheckInterval()), properties.checkBatchSize());
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
        String returnUrl = request.returnUrl() == null ? null : request.returnUrl().toString();
        return new Payment(UUID.randomUUID(), merchantId, idempotencyKey, fingerprint, request.orderId(),
                request.amount(), request.currency(), request.description(), returnUrl, PaymentStatus.PROCESSING,
                number.brand(), number.bin(), number.last4(), provider.name(), null, null, null,
                now.plus(properties.firstCheckAfter()), now, now);
    }

    // The gateway's own page, which the provider sends the customer's browser to after the challenge.
    private URI challengeReturnUrl(UUID paymentId) {
        return URI.create(properties.publicUrl() + ThreeDSecureReturnController.PATH + paymentId);
    }

    private static PaymentResult replay(Payment earlier, String fingerprint) {
        if (!earlier.requestFingerprint().equals(fingerprint)) {
            throw new GatewayException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was sent with a different payment request; use a new key for a new payment");
        }
        return new PaymentResult(earlier, true);
    }

    // Without an outcome (NOT_FOUND, UNKNOWN) the payment stays as it is until its next check.
    private Payment record(Payment payment, ProviderResult result) {
        return switch (result.outcome()) {
            case APPROVED -> finish(payment, PaymentStatus.SUCCEEDED, result.providerReference(), null);
            case DECLINED -> finish(payment, PaymentStatus.DECLINED, result.providerReference(), result.code());
            case FAILED -> finish(payment, PaymentStatus.FAILED, result.providerReference(), result.code());
            case REQUIRES_ACTION -> requireAction(payment, result.providerReference(), result.actionUrl());
            case NOT_FOUND, UNKNOWN -> payment;
        };
    }

    private Payment finish(Payment payment, PaymentStatus status, @Nullable String providerReference,
            @Nullable String code) {
        Instant now = now();
        Optional<Payment> finished = changeStatus(
                () -> payments.finish(payment.id(), status, providerReference, code, now));
        if (finished.isEmpty()) {
            // The request, the checker or the customer's return recorded an outcome first; that one stands.
            return current(payment);
        }
        log.info("Payment {} {}{}", payment.id(), status, code == null ? "" : " (" + code + ")");
        return finished.get();
    }

    // ProviderResult guarantees both values with REQUIRES_ACTION.
    private Payment requireAction(Payment payment, @Nullable String providerReference, @Nullable String actionUrl) {
        Instant now = now();
        Optional<Payment> waiting = changeStatus(() -> payments.requireAction(payment.id(), providerReference,
                actionUrl, now.plus(properties.challengeCheckInterval()), now));
        if (waiting.isEmpty()) {
            // Waiting for its challenge already, or finished: either way there is nothing to change.
            return current(payment);
        }
        log.info("Payment {} REQUIRES_ACTION: the card's bank asks for a 3-D Secure challenge", payment.id());
        return waiting.get();
    }

    /**
     * Writes a status change and its webhook event in one transaction, so the event exists exactly when the change
     * does. An empty change means another writer was first; then there is no event either.
     */
    private Optional<Payment> changeStatus(Supplier<Optional<Payment>> change) {
        return transactions.execute(transaction -> {
            Optional<Payment> changed = change.get();
            changed.ifPresent(payment -> webhooks.add(payment.merchantId(), payment.id(),
                    PaymentMapper.eventTypeOf(payment.status()).getValue(), PaymentMapper.toEvent(payment)));
            return changed;
        });
    }

    private Payment current(Payment payment) {
        return payments.findById(payment.id())
                .orElseThrow(() -> new IllegalStateException("Payment " + payment.id() + " is gone"));
    }

    // PostgreSQL keeps microseconds, so the payment returned right away matches the one read back later.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
