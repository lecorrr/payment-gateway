package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IssuerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @ParameterizedTest
    @CsvSource({
            "4000000000000002, do_not_honor",
            "4000000000009995, insufficient_funds",
            "4000000000000119, processing_error"})
    void shouldDeclineListedTestCards(String number, String declineCode) {
        assertThat(Issuer.declineCode(card(number, 12, 2030, "123"), TODAY)).contains(declineCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5555555555554444", Issuer.SLOW_CARD})
    void shouldApproveAnyOtherCard(String number) {
        assertThat(Issuer.declineCode(card(number, 12, 2030, "123"), TODAY)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"10, 2026, expired_card", "11, 2026, ", "12, 2025, expired_card"})
    void shouldDeclineCardPastItsExpiryMonth(int month, int year, String declineCode) {
        assertThat(Issuer.declineCode(card("4111111111111111", month, year, "123"), TODAY))
                .isEqualTo(Optional.ofNullable(declineCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "4000000000000002"})
    void shouldDeclineCvcOfZeros(String number) {
        assertThat(Issuer.declineCode(card(number, 12, 2030, "000"), TODAY)).contains("incorrect_cvc");
    }

    @Test
    void shouldAskForChallengeOnlyForChallengeCard() {
        assertThat(Issuer.needsChallenge(card(Issuer.CHALLENGE_CARD, 12, 2030, "123"))).isTrue();
        assertThat(Issuer.needsChallenge(card("4111111111111111", 12, 2030, "123"))).isFalse();
        assertThat(Issuer.declineCode(card(Issuer.CHALLENGE_CARD, 12, 2030, "123"), TODAY)).isEmpty();
    }

    private static ChargeRequest.Card card(String number, int expMonth, int expYear, String cvc) {
        return new ChargeRequest.Card(number, expMonth, expYear, cvc, null);
    }
}
