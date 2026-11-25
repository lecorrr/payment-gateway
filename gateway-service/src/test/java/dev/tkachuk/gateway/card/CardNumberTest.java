package dev.tkachuk.gateway.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CardNumberTest {

    @Test
    void shouldIgnoreSpaces() {
        CardNumber number = CardNumber.parse("4111 1111 1111 1111");

        assertThat(number.digits()).isEqualTo("4111111111111111");
        assertThat(number.bin()).isEqualTo("411111");
        assertThat(number.last4()).isEqualTo("1111");
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000000002", "6000000000000000004"})
    void shouldAcceptTwelveToNineteenDigits(String digits) {
        assertThat(CardNumber.parse(digits).digits()).isEqualTo(digits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111112", "5555555555554443", "4000000000000001"})
    void shouldRejectNumberThatFailsLuhnCheck(String digits) {
        assertCardInvalid(digits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "40000000006", "40000000000000000006", "4111-1111-1111-1111", "4111111111111111x",
            "٤١١١١١١١١١١١١١١١"})
    void shouldRejectWrongLengthOrNonDigits(String input) {
        assertCardInvalid(input);
    }

    @ParameterizedTest
    @CsvSource({
            "4111111111111111, VISA",
            "5100000000000008, MASTERCARD",
            "5555555555554444, MASTERCARD",
            "2221000000000009, MASTERCARD",
            "2720000000000005, MASTERCARD",
            "2721000000000004, OTHER",
            "5600000000000003, OTHER",
            "378282246310005, OTHER"})
    void shouldTellBrandFromLeadingDigits(String digits, CardBrand brand) {
        assertThat(CardNumber.parse(digits).brand()).isEqualTo(brand);
    }

    @Test
    void shouldShowOnlyFirstSixAndLastFourDigits() {
        assertThat(CardNumber.parse("4111111111111111")).hasToString("411111******1111");
        assertThat(CardNumber.parse("378282246310005")).hasToString("378282*****0005");
    }

    @Test
    void shouldCompareByDigits() {
        assertThat(CardNumber.parse("4111 1111 1111 1111")).isEqualTo(CardNumber.parse("4111111111111111"))
                .hasSameHashCodeAs(CardNumber.parse("4111111111111111"))
                .isNotEqualTo(CardNumber.parse("5555555555554444"));
    }

    private static void assertCardInvalid(String input) {
        assertThatThrownBy(() -> CardNumber.parse(input))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CARD_INVALID));
    }
}
