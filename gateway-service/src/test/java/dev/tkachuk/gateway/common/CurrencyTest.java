package dev.tkachuk.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CurrencyTest {

    @ParameterizedTest
    @CsvSource({"UAH, 980", "USD, 840", "EUR, 978"})
    void shouldKnowIsoNumericCode(Currency currency, int numericCode) {
        assertThat(currency.numericCode()).isEqualTo(numericCode);
    }
}
