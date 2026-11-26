package dev.tkachuk.risk;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentAttemptTest {

    @Test
    void shouldTakePaymentWithEveryField() {
        assertThatNoException().isThrownBy(() -> attempt("merchant-1/order-1001-1", 15000, "UAH", "tok_1", "411111"));
    }

    // The message names the field as risk.proto does, since that is what the gateway sent.
    @ParameterizedTest
    @CsvSource({
        "'', 15000, UAH, tok_1, 411111, attempt_id",
        "merchant-1/order-1001-1, 0, UAH, tok_1, 411111, amount",
        "merchant-1/order-1001-1, 15000, uah, tok_1, 411111, currency",
        "merchant-1/order-1001-1, 15000, UAH, '', 411111, card_token",
        "merchant-1/order-1001-1, 15000, UAH, tok_1, 41111, card_bin",
        "merchant-1/order-1001-1, 15000, UAH, tok_1, 4111a1, card_bin"
    })
    void shouldNameFieldThatIsWrong(String attemptId, long amount, String currency, String cardToken, String cardBin,
            String field) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> attempt(attemptId, amount, currency, cardToken, cardBin))
                .withMessageStartingWith(field);
    }

    @Test
    void shouldRefuseAttemptIdLongerThanAnyMerchantSends() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> attempt("x".repeat(201), 15000, "UAH", "tok_1", "411111"))
                .withMessageStartingWith("attempt_id");
    }

    private static PaymentAttempt attempt(String attemptId, long amount, String currency, String cardToken,
            String cardBin) {
        return new PaymentAttempt(attemptId, "6f9a2c1e-0000-4000-8000-000000000001", "merchant-1", amount, currency,
                cardToken, cardBin);
    }
}
