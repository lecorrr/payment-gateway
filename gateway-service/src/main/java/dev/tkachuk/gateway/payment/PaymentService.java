package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import dev.tkachuk.gateway.event.EventOutbox;
import dev.tkachuk.gateway.provider.ChargeLookup;
import dev.tkachuk.gateway.provider.ChargeNotifications;
import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderConnector;
import dev.tkachuk.gateway.provider.ProviderConnectors;
import dev.tkachuk.gateway.provider.ProviderResult;
import dev.tkachuk.gateway.provider.ProviderResult.Outcome;
import dev.tkachuk.gateway.provider.ProviderRoute;
import dev.tkachuk.gateway.provider.ProviderRouter;
import dev.tkachuk.gateway.risk.RiskCheck;
import dev.tkachuk.gateway.risk.RiskRequest;
import dev.tkachuk.gateway.risk.RiskVerdict;
import dev.tkachuk.gateway.webhook.WebhookOutbox;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * outcome got lost or whose challenge was left unfinished. A new payment is first checked by risk-service; one it
 * declines is stored as DECLINED and never charged. The provider is called outside any transaction; the payment is
 * stored as PROCESSING before the call, so a crash in between leaves a payment the checker finds, never a charge the
 * gateway doesn't know about. Every status change after that is stored together with its webhook event. A new
 * payment goes to the provider ProviderRouter picks, and on to the next while one turns out unavailable; each
 * payment is looked up with the provider that charged it.
 */
@Service
public class PaymentService implements ChargeNotifications {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RequestFingerprints fingerprints;
    private final ProviderConnectors providers;
    private final ProviderRouter router;
    private final RiskCheck riskCheck;
    private final WebhookOutbox webhooks;
    private final EventOutbox events;
    private final TransactionTemplate transactions;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentService(PaymentRepository payments, RequestFingerprints fingerprints, ProviderConnectors providers,
            ProviderRouter router, RiskCheck riskCheck, WebhookOutbox webhooks, EventOutbox events,
            PlatformTransactionManager transactionManager, PaymentMetrics metrics, PaymentProperties properties,
            Clock clock) {
        this.payments = payments;
        this.fingerprints = fingerprints;
        this.providers = providers;
        this.router = router;
        this.riskCheck = riskCheck;
        this.webhooks = webhooks;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
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
        UUID paymentId = UUID.randomUUID();
        CardNumber number = request.card().number();
        // The same attempt for every try with this key, so risk-service counts a retry once.
        RiskVerdict risk = riskCheck.check(new RiskRequest(merchantId + "/" + idempotencyKey, paymentId, merchantId,
                request.amount(), request.currency(), fingerprints.cardToken(number), number.bin()));
        if (risk != RiskVerdict.ALLOW) {
            return refuse(newPayment(paymentId, merchantId, idempotencyKey, fingerprint, request,
                    providers.ordered().getFirst().name(), now), risk);
        }

        // The provider is picked before the payment is stored, so the payment names it from the start.
        Optional<ProviderRoute> route = router.next(Set.of());
        String provider = route.map(picked -> picked.provider().name())
                .orElseGet(() -> providers.ordered().getFirst().name());
        Payment payment = newPayment(paymentId, merchantId, idempotencyKey, fingerprint, request, provider, now);
        if (!insertIfAbsent(payment, route)) {
            return replayStoredMeanwhile(merchantId, idempotencyKey, fingerprint);
        }
        if (route.isEmpty()) {
            log.warn("Payment {} for order {} created, but every provider is failing", payment.id(),
                    payment.orderId());
            return new PaymentResult(finish(payment, PaymentStatus.FAILED, null, FailureCodes.PROVIDER_UNAVAILABLE),
                    false);
        }
        log.info("Payment {} for order {} created, charging it with {}", payment.id(), payment.orderId(), provider);

        ChargeRequest charge = new ChargeRequest(payment.id(), payment.amount(), payment.currency(), request.card(),
                request.description(), challengeReturnUrl(payment.id()));
        return new PaymentResult(charge(payment, route.get(), charge), false);
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
        return record(payment, lookup(payment));
    }

    /**
     * A provider's webhook says the charge of this payment changed. The payment is looked up with the provider that
     * charged it, unless it is finished already or another provider charged it.
     */
    @Override
    public void chargeChanged(String provider, UUID paymentId) {
        Optional<Payment> found = payments.findById(paymentId);
        if (found.isEmpty()) {
            log.warn("{} reports a change to payment {}, which the gateway does not have", provider, paymentId);
            return;
        }
        Payment payment = found.get();
        if (!payment.provider().equals(provider)) {
            log.warn("{} reports a change to payment {}, which {} charged", provider, paymentId, payment.provider());
            return;
        }
        if (!payment.status().isFinal()) {
            record(payment, lookup(payment));
        }
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
            ProviderResult result = lookup(payment);
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

    /**
     * Charges with the route's provider, and with the next one while a provider turns out unavailable: it never got
     * the charge, so the next can't charge the card twice. Every other result stays with the provider that gave it,
     * a lost answer too. The payment is moved to the next provider before that one is called.
     */
    private Payment charge(Payment payment, ProviderRoute firstRoute, ChargeRequest request) {
        Payment current = payment;
        ProviderRoute route = firstRoute;
        Set<String> tried = new HashSet<>();
        while (true) {
            String provider = route.provider().name();
            ProviderResult result = route.charge(request);
            tried.add(provider);
            Optional<ProviderRoute> next = result.isUnavailable() ? router.next(tried) : Optional.empty();
            if (next.isEmpty()) {
                Payment recorded = record(current, result);
                if (recorded.status() == PaymentStatus.PROCESSING) {
                    String why = result.outcome() == Outcome.PENDING ? "is still working on it" : "gave no outcome";
                    log.info("Payment {} stays PROCESSING: {} {}; first check at {}", current.id(), provider, why,
                            current.nextCheckAt());
                }
                return recorded;
            }
            String nextProvider = next.get().provider().name();
            Optional<Payment> moved;
            try {
                moved = payments.moveToProvider(current.id(), nextProvider, now());
            } catch (RuntimeException e) {
                next.get().release();
                throw e;
            }
            if (moved.isEmpty()) {
                // Something recorded an outcome meanwhile; that one stands.
                next.get().release();
                return current(current);
            }
            log.info("Payment {}: {} can't take it, charging it with {}", current.id(), provider, nextProvider);
            current = moved.get();
            route = next.get();
        }
    }

    /**
     * Stores a payment the risk check refused, already over: no provider sees it. It names the provider it would
     * have gone to, as a payment no provider could take does.
     */
    private PaymentResult refuse(Payment payment, RiskVerdict risk) {
        if (!payments.insertIfAbsent(payment)) {
            return replayStoredMeanwhile(payment.merchantId(), payment.idempotencyKey(), payment.requestFingerprint());
        }
        if (risk == RiskVerdict.DECLINE) {
            log.info("Payment {} for order {} declined by risk-service", payment.id(), payment.orderId());
            return new PaymentResult(finish(payment, PaymentStatus.DECLINED, null, RiskVerdict.DECLINED_CODE), false);
        }
        log.warn("Payment {} for order {} failed: risk-service could not check it", payment.id(), payment.orderId());
        return new PaymentResult(finish(payment, PaymentStatus.FAILED, null, RiskVerdict.UNAVAILABLE_CODE), false);
    }

    // A request with the same key was stored between the lookup at the start of create and this one's insert.
    private PaymentResult replayStoredMeanwhile(UUID merchantId, String idempotencyKey, String fingerprint) {
        Payment first = payments.findByIdempotencyKey(merchantId, idempotencyKey).orElseThrow(
                () -> new IllegalStateException("Idempotency-Key " + idempotencyKey + " is taken, but not found"));
        return replay(first, fingerprint);
    }

    // A route's permit must go back to its circuit unless the provider is called.
    private boolean insertIfAbsent(Payment payment, Optional<ProviderRoute> route) {
        boolean inserted;
        try {
            inserted = payments.insertIfAbsent(payment);
        } catch (RuntimeException e) {
            route.ifPresent(ProviderRoute::release);
            throw e;
        }
        if (!inserted) {
            route.ifPresent(ProviderRoute::release);
        }
        return inserted;
    }

    private Payment newPayment(UUID paymentId, UUID merchantId, String idempotencyKey, String fingerprint,
            NewPayment request, String provider, Instant now) {
        CardNumber number = request.card().number();
        URI requestedReturnUrl = request.returnUrl();
        String returnUrl = requestedReturnUrl == null ? null : requestedReturnUrl.toString();
        return new Payment(paymentId, merchantId, idempotencyKey, fingerprint, request.orderId(),
                request.amount(), request.currency(), request.description(), returnUrl, PaymentStatus.PROCESSING,
                number.brand(), number.bin(), number.last4(), provider, null, null, 0, null,
                now.plus(properties.firstCheckAfter()), now, now);
    }

    // A payment whose provider this gateway no longer has can't be looked up; it waits, and the log says why.
    private ProviderResult lookup(Payment payment) {
        Optional<ProviderConnector> provider = providers.byName(payment.provider());
        if (provider.isEmpty()) {
            log.error("Payment {} was charged with {}, which this gateway has no connector for", payment.id(),
                    payment.provider());
            return ProviderResult.unknown();
        }
        return provider.get().lookup(new ChargeLookup(payment.id(), payment.providerReference(),
                payment.createdAt()));
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

    // Without an outcome (PENDING, NOT_FOUND, UNKNOWN) the payment stays as it is until its next check.
    private Payment record(Payment payment, ProviderResult result) {
        return switch (result.outcome()) {
            case APPROVED -> finish(payment, PaymentStatus.SUCCEEDED, result.providerReference(), null);
            case DECLINED -> finish(payment, PaymentStatus.DECLINED, result.providerReference(), result.code());
            case FAILED -> finish(payment, PaymentStatus.FAILED, result.providerReference(), result.code());
            case REQUIRES_ACTION -> requireAction(payment, result.providerReference(), result.actionUrl());
            case PENDING -> keepProviderReference(payment, result.providerReference());
            case NOT_FOUND, UNKNOWN -> payment;
        };
    }

    // A provider that is still working on a charge may have given it an id already; lookups ask by it from now on.
    private Payment keepProviderReference(Payment payment, @Nullable String providerReference) {
        if (providerReference == null || payment.providerReference() != null) {
            return payment;
        }
        return payments.rememberProviderReference(payment.id(), providerReference, now())
                .orElseGet(() -> current(payment));
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
        metrics.paymentFinished(finished.get());
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
     * Writes a status change and its events, for the merchant's webhook and for the payment-events topic, in one
     * transaction, so the events exist exactly when the change does. An empty change means another writer was
     * first; then there are no events either.
     */
    private Optional<Payment> changeStatus(Supplier<Optional<Payment>> change) {
        return transactions.execute(transaction -> {
            Optional<Payment> changed = change.get();
            changed.ifPresent(this::addEvents);
            return changed;
        });
    }

    // One change, one event id, on both channels.
    private void addEvents(Payment payment) {
        UUID eventId = UUID.randomUUID();
        String type = PaymentMapper.eventTypeOf(payment.status()).getValue();
        webhooks.add(eventId, payment.merchantId(), payment.id(), type, PaymentMapper.toEvent(payment));
        events.add(eventId, payment.id(), type, payment.updatedAt(), PaymentMapper.toEventData(payment));
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
