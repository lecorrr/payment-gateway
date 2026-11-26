package dev.tkachuk.acquirer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The refunds in memory, by reference. Adding one checks what is left of its charge under the store's lock, so two
 * refunds added at once can't both take the same amount. Past sim.max-charges the oldest is forgotten.
 */
@Component
class RefundStore {

    /**
     * A refund, and for one still pending, when it finishes and how: outcomeStatus and outcomeFailureCode are what
     * it becomes at finishAt. For a refund that is not pending they are null.
     */
    record StoredRefund(Refund refund, Instant finishAt, String outcomeStatus, String outcomeFailureCode) {

        StoredRefund with(Refund changed) {
            return new StoredRefund(changed, finishAt, outcomeStatus, outcomeFailureCode);
        }
    }

    enum Kind { ADDED, EXISTS, TOO_LARGE }

    /** stored: the new refund for ADDED, the one stored earlier under its reference for EXISTS, null otherwise. */
    record Added(Kind kind, StoredRefund stored) {
    }

    private final Map<String, StoredRefund> byReference;

    RefundStore(SimProperties properties) {
        int maxRefunds = properties.maxCharges();
        this.byReference = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, StoredRefund> eldest) {
                return size() > maxRefunds;
            }
        };
    }

    /**
     * Adds the refund unless its reference has one already, and only if its amount fits in what the charge's other
     * refunds left of chargeAmount.
     */
    synchronized Added addIfFits(StoredRefund refund, long chargeAmount) {
        StoredRefund existing = byReference.get(refund.refund().reference());
        if (existing != null) {
            return new Added(Kind.EXISTS, existing);
        }
        if (refund.refund().amount() > chargeAmount - taken(refund.refund().chargeId())) {
            return new Added(Kind.TOO_LARGE, null);
        }
        byReference.put(refund.refund().reference(), refund);
        return new Added(Kind.ADDED, refund);
    }

    synchronized Optional<StoredRefund> find(String reference) {
        return Optional.ofNullable(byReference.get(reference));
    }

    /** The charge's refunds, in the order they were made. */
    synchronized List<StoredRefund> ofCharge(String chargeId) {
        List<StoredRefund> found = new ArrayList<>();
        for (StoredRefund stored : byReference.values()) {
            if (stored.refund().chargeId().equals(chargeId)) {
                found.add(stored);
            }
        }
        return found;
    }

    /** What is left of chargeAmount after the charge's refunds that succeeded or may still succeed. */
    synchronized long leftOf(String chargeId, long chargeAmount) {
        return chargeAmount - taken(chargeId);
    }

    /** Replaces the refund stored under the reference with what change makes of it, under the store's lock. */
    synchronized Optional<StoredRefund> update(String reference, UnaryOperator<StoredRefund> change) {
        StoredRefund current = byReference.get(reference);
        if (current == null) {
            return Optional.empty();
        }
        StoredRefund changed = change.apply(current);
        byReference.replace(reference, changed);
        return Optional.of(changed);
    }

    private long taken(String chargeId) {
        long taken = 0;
        for (StoredRefund stored : byReference.values()) {
            if (stored.refund().chargeId().equals(chargeId) && stored.refund().takesAmount()) {
                taken += stored.refund().amount();
            }
        }
        return taken;
    }
}
