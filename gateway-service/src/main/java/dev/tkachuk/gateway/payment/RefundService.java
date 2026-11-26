package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import dev.tkachuk.gateway.event.EventOutbox;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderConnector;
import dev.tkachuk.gateway.provider.ProviderConnectors;
import dev.tkachuk.gateway.provider.ProviderRefundResult;
import dev.tkachuk.gateway.provider.ProviderRefundResult.Outcome;
import dev.tkachuk.gateway.provider.RefundLookup;
import dev.tkachuk.gateway.provider.RefundNotifications;
import dev.tkachuk.gateway.provider.RefundRequest;
import dev.tkachuk.gateway.webhook.WebhookOutbox;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds of SUCCEEDED payments, in full or in parts, each sent to the provider that charged the payment: one refund
 * per Idempotency-Key. A refund is stored PROCESSING before the provider is called, in the transaction that holds
 * the payment's lock, after checking that its amount fits in what the payment's earlier refunds left; so refunds
 * sent side by side can never give back more than was charged. The provider is called outside any transaction, as a
 * charge is. A refund whose outcome got lost is looked up in the background, as a payment is, and one the provider
 * still has no record of when give-up-after has passed becomes FAILED. Every outcome is stored together with its
 * webhook event and, for a SUCCEEDED refund, the payment's refunded amount.
 */
@Service
public class RefundService implements RefundNotifications {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final RequestFingerprints fingerprints;
    private final ProviderConnectors providers;
    private final WebhookOutbox webhooks;
    private final EventOutbox events;
    private final TransactionTemplate transactions;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final Clock clock;

    RefundService(RefundRepository refunds, PaymentRepository payments, RequestFingerprints fingerprints,
            ProviderConnectors providers, WebhookOutbox webhooks, EventOutbox events,
            PlatformTransactionManager transactionManager, PaymentMetrics metrics, PaymentProperties properties,
            Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.fingerprints = fingerprints;
        this.providers = providers;
        this.webhooks = webhooks;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Refunds amount of the merchant's payment. A repeat of an earlier request with the same Idempotency-Key gets
     * the refund that request made, as it is now; the same key with a different request is refused.
     */
    public RefundResult create(UUID merchantId, UUID paymentId, String idempotencyKey, long amount) {
        String fingerprint = fingerprints.ofRefund(paymentId, amount);
        Optional<Refund> earlier = refunds.findByIdempotencyKey(merchantId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), fingerprint);
        }
        Reservation reservation = transactions.execute(
                transaction -> reserve(merchantId, paymentId, idempotencyKey, fingerprint, amount));
        if (reservation == null) {
            throw new IllegalStateException("The refund's transaction gave no result");
        }
        if (reservation.payment() == null) {
            return replay(reservation.refund(), fingerprint);
        }
        Refund refund = reservation.refund();
        log.info("Refund {} of {} for payment {} created, sending it to {}", refund.id(), refund.amount(),
                paymentId, refund.provider());
        return new RefundResult(send(refund, reservation.payment()), false);
    }

    public Refund get(UUID merchantId, UUID paymentId, UUID refundId) {
        return refunds.find(merchantId, paymentId, refundId)
                .orElseThrow(() -> new GatewayException(ErrorCode.REFUND_NOT_FOUND,
                        "No refund " + refundId + " of payment " + paymentId));
    }

    /** The payment's refunds, the newest first; a payment of another merchant is not found. */
    public List<Refund> list(UUID merchantId, UUID paymentId) {
        if (payments.find(merchantId, paymentId).isEmpty()) {
            throw new GatewayException(ErrorCode.PAYMENT_NOT_FOUND, "No payment " + paymentId);
        }
        return refunds.ofPayment(merchantId, paymentId);
    }

    /** A provider's webhook says the refunds of this payment changed: each one still waiting is looked up. */
    @Override
    public void refundsChanged(String provider, UUID paymentReference) {
        for (Refund refund : refunds.processingOfPayment(paymentReference)) {
            if (refund.provider().equals(provider)) {
                record(refund, lookup(refund));
            }
        }
    }

    /**
     * Looks up the PROCESSING refunds that are due. One the provider still has no record of when give-up-after has
     * passed becomes FAILED; while the provider can't be asked, a refund stays as it is. Returns how many refunds
     * were looked up.
     */
    public int checkPending() {
        Instant now = now();
        List<Refund> due = refunds.claimDue(now, now.plus(properties.checkInterval()), properties.checkBatchSize());
        for (Refund refund : due) {
            ProviderRefundResult result = lookup(refund);
            boolean givingUp = result.outcome() == Outcome.NOT_FOUND
                    && !now.isBefore(refund.createdAt().plus(properties.giveUpAfter()));
            if (givingUp) {
                finish(refund, RefundStatus.FAILED, null, FailureCodes.PROVIDER_NO_RECORD);
            } else {
                record(refund, result);
            }
        }
        return due.size();
    }

    /**
     * In one transaction, under the payment's lock: the payment must be the merchant's and SUCCEEDED, and the amount
     * must fit in what its other refunds left. A refund stored meanwhile with the same key comes back as a replay,
     * without a payment; otherwise the new refund comes back with its payment.
     */
    private Reservation reserve(UUID merchantId, UUID paymentId, String idempotencyKey, String fingerprint,
            long amount) {
        Payment payment = payments.lockForRefund(merchantId, paymentId)
                .orElseThrow(() -> new GatewayException(ErrorCode.PAYMENT_NOT_FOUND, "No payment " + paymentId));
        // A request with the same key for this payment waited for the lock, and stored its refund meanwhile.
        Optional<Refund> sameKey = refunds.findByIdempotencyKey(merchantId, idempotencyKey);
        if (sameKey.isPresent()) {
            return new Reservation(sameKey.get(), null);
        }
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            throw new GatewayException(ErrorCode.PAYMENT_NOT_REFUNDABLE,
                    "Only a SUCCEEDED payment can be refunded; this one is " + payment.status());
        }
        if (payment.providerReference() == null) {
            throw new IllegalStateException("Payment " + paymentId + " SUCCEEDED without a provider reference");
        }
        long left = payment.amount() - refunds.reservedAmount(paymentId);
        if (amount > left) {
            throw new GatewayException(ErrorCode.REFUND_AMOUNT_TOO_LARGE, left == 0
                    ? "Nothing of this payment is left to refund"
                    : "Only " + left + " of this payment's " + payment.amount() + " can still be refunded");
        }
        Instant now = now();
        Refund refund = new Refund(UUID.randomUUID(), paymentId, merchantId, idempotencyKey, fingerprint, amount,
                payment.currency(), RefundStatus.PROCESSING, payment.provider(), null, null,
                now.plus(properties.firstCheckAfter()), now, now);
        if (!refunds.insertIfAbsent(refund)) {
            // The key went to a refund of another payment, which held a lock of its own.
            Refund first = refunds.findByIdempotencyKey(merchantId, idempotencyKey).orElseThrow(
                    () -> new IllegalStateException("Idempotency-Key " + idempotencyKey + " is taken, but not found"));
            return new Reservation(first, null);
        }
        return new Reservation(refund, payment);
    }

    // A refund whose provider this gateway no longer has can't be sent; it waits, and the log says why.
    private Refund send(Refund refund, Payment payment) {
        Optional<ProviderConnector> provider = providers.byName(refund.provider());
        if (provider.isEmpty()) {
            log.error("Refund {} is for {}, which this gateway has no connector for", refund.id(), refund.provider());
            return refund;
        }
        ProviderRefundResult result = provider.get().refund(new RefundRequest(refund.id(), payment.id(),
                requireChargeReference(payment), refund.amount(), refund.currency()));
        Refund recorded = record(refund, result);
        if (recorded.status() == RefundStatus.PROCESSING) {
            String why = result.outcome() == Outcome.PENDING ? "is still working on it" : "gave no outcome";
            log.info("Refund {} stays PROCESSING: {} {}; first check at {}", refund.id(), refund.provider(), why,
                    refund.nextCheckAt());
        }
        return recorded;
    }

    private ProviderRefundResult lookup(Refund refund) {
        Optional<ProviderConnector> provider = providers.byName(refund.provider());
        if (provider.isEmpty()) {
            log.error("Refund {} was sent to {}, which this gateway has no connector for", refund.id(),
                    refund.provider());
            return ProviderRefundResult.unknown();
        }
        Payment payment = payments.findById(refund.paymentId()).orElseThrow(
                () -> new IllegalStateException("Payment " + refund.paymentId() + " of refund " + refund.id()
                        + " is gone"));
        return provider.get().lookupRefund(new RefundLookup(refund.id(), requireChargeReference(payment),
                refund.providerReference()));
    }

    // Without an outcome (PENDING, NOT_FOUND, UNKNOWN) the refund stays as it is until its next check.
    private Refund record(Refund refund, ProviderRefundResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED -> finish(refund, RefundStatus.SUCCEEDED, result.providerReference(), null);
            case FAILED -> finish(refund, RefundStatus.FAILED, result.providerReference(), result.code());
            case PENDING -> keepProviderReference(refund, result.providerReference());
            case NOT_FOUND, UNKNOWN -> refund;
        };
    }

    private Refund keepProviderReference(Refund refund, @Nullable String providerReference) {
        if (providerReference == null || refund.providerReference() != null) {
            return refund;
        }
        return refunds.rememberProviderReference(refund.id(), providerReference, now())
                .orElseGet(() -> current(refund));
    }

    /**
     * Writes the outcome, the payment's refunded amount for a SUCCEEDED refund, and its events, for the merchant's
     * webhook and for the payment-events topic, in one transaction. An empty change means another writer was first;
     * then there is nothing else to write.
     */
    private Refund finish(Refund refund, RefundStatus status, @Nullable String providerReference,
            @Nullable String code) {
        Instant now = now();
        Optional<Refund> finished = transactions.execute(transaction -> {
            Optional<Refund> changed = refunds.finish(refund.id(), status, providerReference, code, now);
            changed.ifPresent(done -> {
                if (done.status() == RefundStatus.SUCCEEDED) {
                    payments.addRefunded(done.paymentId(), done.amount(), now);
                }
                addEvents(done);
            });
            return changed;
        });
        if (finished == null || finished.isEmpty()) {
            // The request, the checker or a webhook recorded an outcome first; that one stands.
            return current(refund);
        }
        log.info("Refund {} of payment {} {}{}", refund.id(), refund.paymentId(), status,
                code == null ? "" : " (" + code + ")");
        metrics.refundFinished(finished.get());
        return finished.get();
    }

    // One change, one event id, on both channels.
    private void addEvents(Refund refund) {
        UUID eventId = UUID.randomUUID();
        String type = RefundMapper.eventTypeOf(refund.status()).getValue();
        webhooks.add(eventId, refund.merchantId(), refund.paymentId(), type, RefundMapper.toEvent(refund));
        events.add(eventId, refund.paymentId(), type, refund.updatedAt(), RefundMapper.toEventData(refund));
    }

    private static RefundResult replay(Refund earlier, String fingerprint) {
        if (!earlier.requestFingerprint().equals(fingerprint)) {
            throw new GatewayException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was sent with a different refund request; use a new key for a new refund");
        }
        return new RefundResult(earlier, true);
    }

    private static String requireChargeReference(Payment payment) {
        String reference = payment.providerReference();
        if (reference == null) {
            throw new IllegalStateException("Payment " + payment.id() + " has no provider reference to refund");
        }
        return reference;
    }

    private Refund current(Refund refund) {
        return refunds.findById(refund.id())
                .orElseThrow(() -> new IllegalStateException("Refund " + refund.id() + " is gone"));
    }

    // PostgreSQL keeps microseconds, so the refund returned right away matches the one read back later.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** payment: null when the refund is one stored earlier with the same key, to be replayed. */
    private record Reservation(Refund refund, @Nullable Payment payment) {
    }
}
