package dev.tkachuk.acquirer;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import dev.tkachuk.acquirer.RefundStore.StoredRefund;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Refunds of approved charges, in full or in parts, decided by the card the charge was made with (Issuer). A refund
 * through /v1/refunds is finished at once; one through monobank's invoice/cancel stays "pending" for
 * sim.refund-delay, as monobank's do, and is then finished in the background and published as RefundChanged.
 */
@Service
class RefundService {

    enum Kind { CREATED, REPLAYED, CONFLICT, NO_CHARGE, NOT_REFUNDABLE, TOO_LARGE }

    /** refund: the refund for CREATED and REPLAYED; null for the others. */
    record Result(Kind kind, Refund refund) {
    }

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundStore store;
    private final ChargeService charges;
    private final SimProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ScheduledExecutorService finisher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "refund-finisher");
        thread.setDaemon(true);
        return thread;
    });

    RefundService(RefundStore store, ChargeService charges, SimProperties properties, Clock clock,
            ApplicationEventPublisher events) {
        this.store = store;
        this.charges = charges;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Refunds the request's amount of its charge. A repeat with the same reference gets the stored refund as it is
     * now; the same reference with a different request is a CONFLICT. finishLater: pending first, finished after
     * sim.refund-delay, as monobank does; otherwise finished at once, and the slow card's refund answered late.
     */
    Result refund(RefundRequest request, boolean finishLater) {
        Optional<StoredCharge> found = charges.findById(request.chargeId());
        if (found.isEmpty()) {
            return new Result(Kind.NO_CHARGE, null);
        }
        StoredCharge charged = found.get();
        Charge charge = charged.charge();
        if (!Charge.APPROVED.equals(charge.status())) {
            return new Result(Kind.NOT_REFUNDABLE, null);
        }
        Instant now = now();
        boolean declined = charged.refunds() == Issuer.RefundBehaviour.DECLINED;
        String outcome = declined ? Refund.FAILED : Refund.SUCCEEDED;
        String failureCode = declined ? Issuer.REFUND_DECLINED : null;
        String id = "re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Refund refund = new Refund(id, charge.id(), request.reference(), request.amount(), charge.currency(),
                finishLater ? Refund.PENDING : outcome, finishLater ? null : failureCode, now, now);
        StoredRefund toStore = finishLater
                ? new StoredRefund(refund, now.plus(properties.refundDelay()), outcome, failureCode)
                : new StoredRefund(refund, null, null, null);

        RefundStore.Added added = store.addIfFits(toStore, charge.amount());
        if (added.kind() == RefundStore.Kind.TOO_LARGE) {
            return new Result(Kind.TOO_LARGE, null);
        }
        if (added.kind() == RefundStore.Kind.EXISTS) {
            Refund earlier = added.stored().refund();
            boolean same = earlier.chargeId().equals(request.chargeId()) && earlier.amount() == request.amount();
            return same ? new Result(Kind.REPLAYED, current(added.stored())) : new Result(Kind.CONFLICT, null);
        }
        log.info("Refund {} of charge {} for reference {}: {}", id, charge.id(), request.reference(),
                refund.status());
        if (finishLater) {
            finisher.schedule(() -> finishIfDue(request.reference()), properties.refundDelay().toMillis(),
                    TimeUnit.MILLISECONDS);
        } else if (charged.refunds() == Issuer.RefundBehaviour.SLOW) {
            waitSlowDelay();
        }
        return new Result(Kind.CREATED, refund);
    }

    /** The refund with this reference as it is now: one past its finishing time is finished first. */
    Optional<Refund> find(String reference) {
        return store.find(reference).map(this::current);
    }

    /** The charge's refunds as they are now, in the order they were made. */
    List<Refund> ofCharge(String chargeId) {
        return store.ofCharge(chargeId).stream().map(this::current).toList();
    }

    /** What can still be refunded of the charge. */
    long leftOf(Charge charge) {
        return store.leftOf(charge.id(), charge.amount());
    }

    @PreDestroy
    void stopFinisher() {
        finisher.shutdownNow();
    }

    private Refund current(StoredRefund stored) {
        if (!isDue(stored)) {
            return stored.refund();
        }
        return finishIfDue(stored.refund().reference()).orElse(stored.refund());
    }

    // Under the store's lock, where it may have been finished since it was read; published after the lock.
    private Optional<Refund> finishIfDue(String reference) {
        AtomicReference<Refund> finished = new AtomicReference<>();
        Optional<StoredRefund> result = store.update(reference, stored -> {
            if (!isDue(stored)) {
                return stored;
            }
            Refund refund = stored.refund().finished(stored.outcomeStatus(), stored.outcomeFailureCode(), now());
            log.info("Refund {} of charge {}: {}", refund.id(), refund.chargeId(), refund.status());
            finished.set(refund);
            return stored.with(refund);
        });
        Refund refund = finished.get();
        if (refund != null) {
            events.publishEvent(new RefundChanged(refund));
        }
        return result.map(StoredRefund::refund);
    }

    private boolean isDue(StoredRefund stored) {
        return stored.refund().isPending() && !now().isBefore(stored.finishAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void waitSlowDelay() {
        try {
            Thread.sleep(properties.slowDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
