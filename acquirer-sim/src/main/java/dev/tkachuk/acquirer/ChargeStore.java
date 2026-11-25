package dev.tkachuk.acquirer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The charges by reference, in memory. Past sim.max-charges the oldest is forgotten. */
@Component
class ChargeStore {

    /** requestHash: a SHA-256 of what was charged, to tell a repeat of the request from a different one. */
    record StoredCharge(Charge charge, String requestHash) {
    }

    private final Map<String, StoredCharge> byReference;

    ChargeStore(SimProperties properties) {
        int maxCharges = properties.maxCharges();
        this.byReference = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, StoredCharge> eldest) {
                return size() > maxCharges;
            }
        };
    }

    /** Stores the charge unless its reference has one already; returns the charge stored under the reference. */
    synchronized StoredCharge putIfAbsent(StoredCharge charge) {
        StoredCharge existing = byReference.putIfAbsent(charge.charge().reference(), charge);
        return existing == null ? charge : existing;
    }

    synchronized Optional<Charge> find(String reference) {
        return Optional.ofNullable(byReference.get(reference)).map(StoredCharge::charge);
    }
}
