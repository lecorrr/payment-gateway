package dev.tkachuk.gateway.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CardTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12", "12345", "12a", " 123"})
    void shouldRejectCvcThatIsNotThreeOrFourDigits(String value) {
        assertThatThrownBy(() -> new Cvc(value))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CARD_INVALID));
    }

    @Test
    void shouldHideCvc() {
        assertThat(new Cvc("123")).hasToString("***");
        assertThat(new Cvc("1234").value()).isEqualTo("1234");
    }

    @Test
    void shouldLeaveSecretsAndHolderOutOfToString() {
        Card card = new Card(CardNumber.parse("4111111111111111"), CardExpiry.of(12, 2030), new Cvc("987"),
                "OLENA KOVALENKO");

        assertThat(card.toString())
                .isEqualTo("Card[411111******1111, expires 12/2030]")
                .doesNotContain("4111111111111111", "987", "OLENA");
    }
}
