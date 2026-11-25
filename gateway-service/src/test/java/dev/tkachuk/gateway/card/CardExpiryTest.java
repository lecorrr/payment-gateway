package dev.tkachuk.gateway.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CardExpiryTest {

    @Test
    void shouldWorkUntilEndOfExpiryMonth() {
        CardExpiry expiry = CardExpiry.of(9, 2030);

        assertThat(expiry.isExpiredOn(LocalDate.of(2029, 12, 31))).isFalse();
        assertThat(expiry.isExpiredOn(LocalDate.of(2030, 9, 30))).isFalse();
        assertThat(expiry.isExpiredOn(LocalDate.of(2030, 10, 1))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1})
    void shouldRejectMonthOutsideYear(int month) {
        assertThatIllegalArgumentException().isThrownBy(() -> CardExpiry.of(month, 2030));
    }

    @Test
    void shouldPrintMonthAndFullYear() {
        assertThat(CardExpiry.of(9, 2030)).hasToString("09/2030");
    }
}
