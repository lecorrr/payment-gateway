package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.card.Cvc;
import dev.tkachuk.gateway.common.Currency;
import java.time.Duration;

/** Requests and settings for the unit tests of this package. */
final class TestPayments {

    static final String FINGERPRINT_KEY = "test-fingerprint-key-0123456789abcdef";

    private TestPayments() {
    }

    static PaymentProperties properties() {
        return new PaymentProperties(FINGERPRINT_KEY, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofMinutes(10), 20);
    }

    static Card card() {
        return card("4111111111111111", 12, 2030, "123", "OLENA KOVALENKO");
    }

    static Card card(String number, int expiryMonth, int expiryYear, String cvc, String holder) {
        return new Card(CardNumber.parse(number), CardExpiry.of(expiryMonth, expiryYear), new Cvc(cvc), holder);
    }

    static NewPayment request() {
        return new NewPayment("ORDER-1001", 15000, Currency.UAH, "Two tickets", card());
    }
}
