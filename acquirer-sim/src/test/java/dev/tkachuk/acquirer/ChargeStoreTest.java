package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChargeStoreTest {

    private final ChargeStore store = new ChargeStore(new SimProperties("test-acquirer-key-0123456789",
            Duration.ZERO, 2));

    @Test
    void shouldKeepFirstChargeOfReference() {
        StoredCharge first = stored("ref-1", "ch_1");

        assertThat(store.putIfAbsent(first)).isSameAs(first);
        assertThat(store.putIfAbsent(stored("ref-1", "ch_2"))).isSameAs(first);
        assertThat(store.find("ref-1")).contains(first.charge());
    }

    @Test
    void shouldForgetOldestChargeBeyondLimit() {
        store.putIfAbsent(stored("ref-1", "ch_1"));
        store.putIfAbsent(stored("ref-2", "ch_2"));
        store.putIfAbsent(stored("ref-3", "ch_3"));

        assertThat(store.find("ref-1")).isEmpty();
        assertThat(store.find("ref-2")).isPresent();
        assertThat(store.find("ref-3")).isPresent();
    }

    private static StoredCharge stored(String reference, String id) {
        Charge charge = new Charge(id, reference, Charge.APPROVED, null, "123456", 15000, "UAH",
                Instant.parse("2026-11-25T10:00:00Z"));
        return new StoredCharge(charge, "hash-of-" + reference);
    }
}
