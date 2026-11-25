package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChargeStoreTest {

    private final ChargeStore store = new ChargeStore(new SimProperties("test-acquirer-key-0123456789",
            "http://localhost:8091", Duration.ZERO, Duration.ofMinutes(15), 2));

    @Test
    void shouldKeepFirstChargeOfReference() {
        StoredCharge first = stored("ref-1", "ch_1");

        assertThat(store.putIfAbsent(first)).isSameAs(first);
        assertThat(store.putIfAbsent(stored("ref-1", "ch_2"))).isSameAs(first);
        assertThat(store.find("ref-1")).contains(first);
        assertThat(store.findById("ch_1")).contains(first);
        assertThat(store.findById("ch_2")).isEmpty();
    }

    @Test
    void shouldForgetOldestChargeBeyondLimit() {
        store.putIfAbsent(stored("ref-1", "ch_1"));
        store.putIfAbsent(stored("ref-2", "ch_2"));
        store.putIfAbsent(stored("ref-3", "ch_3"));

        assertThat(store.find("ref-1")).isEmpty();
        assertThat(store.findById("ch_1")).isEmpty();
        assertThat(store.find("ref-2")).isPresent();
        assertThat(store.findById("ch_3")).isPresent();
    }

    @Test
    void shouldReplaceChargeWithItsChange() {
        StoredCharge first = stored("ref-1", "ch_1");
        store.putIfAbsent(first);

        StoredCharge changed = store.update("ref-1", stored -> stored.with(stored.charge().declined("do_not_honor")))
                .orElseThrow();

        assertThat(changed.charge().status()).isEqualTo(Charge.DECLINED);
        assertThat(store.find("ref-1")).contains(changed);
        assertThat(store.findById("ch_1")).contains(changed);
        assertThat(store.update("ref-unknown", stored -> stored)).isEmpty();
    }

    private static StoredCharge stored(String reference, String id) {
        Charge charge = new Charge(id, reference, Charge.APPROVED, null, "123456", null, 15000, "UAH",
                Instant.parse("2026-11-25T10:00:00Z"));
        return new StoredCharge(charge, "hash-of-" + reference, null, null);
    }
}
