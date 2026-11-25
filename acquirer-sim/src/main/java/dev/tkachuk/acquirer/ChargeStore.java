package dev.tkachuk.acquirer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The charges in memory, by reference and by id (for the challenge pages). Past sim.max-charges the oldest is
 * forgotten.
 */
@Component
class ChargeStore {

    /**
     * requestHash: a SHA-256 of what was charged, to tell a repeat of the request from a different one. returnUrl
     * and challengeExpiresAt belong to a charge that got a 3-D Secure challenge; for others they are null.
     */
    record StoredCharge(Charge charge, String requestHash, String returnUrl, Instant challengeExpiresAt) {

        StoredCharge with(Charge changed) {
            return new StoredCharge(changed, requestHash, returnUrl, challengeExpiresAt);
        }
    }

    private final Map<String, String> referenceById = new HashMap<>();
    private final Map<String, StoredCharge> byReference;

    ChargeStore(SimProperties properties) {
        int maxCharges = properties.maxCharges();
        this.byReference = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, StoredCharge> eldest) {
                if (size() <= maxCharges) {
                    return false;
                }
                referenceById.remove(eldest.getValue().charge().id());
                return true;
            }
        };
    }

    /** Stores the charge unless its reference has one already; returns the charge stored under the reference. */
    synchronized StoredCharge putIfAbsent(StoredCharge charge) {
        StoredCharge existing = byReference.putIfAbsent(charge.charge().reference(), charge);
        if (existing != null) {
            return existing;
        }
        referenceById.put(charge.charge().id(), charge.charge().reference());
        return charge;
    }

    synchronized Optional<StoredCharge> find(String reference) {
        return Optional.ofNullable(byReference.get(reference));
    }

    synchronized Optional<StoredCharge> findById(String chargeId) {
        return Optional.ofNullable(referenceById.get(chargeId)).map(byReference::get);
    }

    /** The charges made at from or later, the newest first. */
    synchronized List<StoredCharge> createdSince(Instant from) {
        List<StoredCharge> found = new ArrayList<>();
        for (StoredCharge stored : byReference.values()) {
            if (!stored.charge().createdAt().isBefore(from)) {
                found.add(stored);
            }
        }
        // Kept in the order they were made.
        return found.reversed();
    }

    /**
     * Replaces the charge stored under the reference with what change makes of it, all under the store's lock, so
     * two changes can't both start from the same charge. Empty if there is none.
     */
    synchronized Optional<StoredCharge> update(String reference, UnaryOperator<StoredCharge> change) {
        StoredCharge current = byReference.get(reference);
        if (current == null) {
            return Optional.empty();
        }
        StoredCharge changed = change.apply(current);
        byReference.replace(reference, changed);
        return Optional.of(changed);
    }
}
