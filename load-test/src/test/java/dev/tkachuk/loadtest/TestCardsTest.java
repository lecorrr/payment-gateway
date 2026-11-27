package dev.tkachuk.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestCardsTest {

    // The Luhn check as a card network makes it, written apart from TestCards' own, so the two can't share a mistake.
    private static boolean passesLuhn(String number) {
        int sum = 0;
        boolean doubled = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = Character.digit(number.charAt(i), 10);
            if (doubled) {
                digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5555555555554444", "4000000000003220", "4000090000000003"})
    void shouldGiveTheCheckDigitOfKnownCards(String card) {
        String withoutCheckDigit = card.substring(0, card.length() - 1);

        assertThat(TestCards.luhnDigit(withoutCheckDigit)).isEqualTo(card.charAt(card.length() - 1) - '0');
    }

    @Test
    void shouldMakeVisaNumbersOfTheTestRangeThatPassTheLuhnCheck() {
        TestCards cards = new TestCards(123_456_789L);

        for (int i = 0; i < 1_000; i++) {
            String number = cards.nextNumber();
            assertThat(number).hasSize(16).startsWith("411111").containsOnlyDigits();
            assertThat(passesLuhn(number)).as(number).isTrue();
        }
    }

    @Test
    void shouldNeverGiveTheSameCardTwice() {
        TestCards cards = new TestCards(0);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 50_000; i++) {
            assertThat(seen.add(cards.nextNumber())).isTrue();
        }
    }

    @Test
    void shouldStayInTheRangeAtItsEnd() {
        TestCards cards = new TestCards(999_999_999L);

        assertThat(cards.nextNumber()).isEqualTo("411111999999999" + TestCards.luhnDigit("411111999999999"));
        assertThat(cards.nextNumber()).isEqualTo("411111000000000" + TestCards.luhnDigit("411111000000000"));
    }
}
