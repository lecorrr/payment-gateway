package dev.tkachuk.gateway.payment;

import static dev.tkachuk.gateway.payment.TestPayments.card;
import static dev.tkachuk.gateway.payment.TestPayments.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.gateway.common.Currency;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RequestFingerprintsTest {

    private static final URI RETURN_URL = URI.create("https://shop.example.com/checkout/done");

    private final RequestFingerprints fingerprints = new RequestFingerprints(TestPayments.properties());

    @Test
    void shouldGiveSameFingerprintForSameRequest() {
        assertThat(fingerprints.of(request())).isEqualTo(fingerprints.of(request())).hasSize(64);
    }

    // Stored payments keep their fingerprints across versions, so a replay of an old request must still match.
    // This value was computed separately, with the format the fingerprint had before returnUrl existed.
    @Test
    void shouldKeepFingerprintOfRequestWithoutReturnUrl() {
        assertThat(fingerprints.of(request()))
                .isEqualTo("fe6b2a400d6434833de7077441d4c13a1e59a4645a82acfc28273c4c1e14d754");
    }

    static Stream<NewPayment> changedRequests() {
        NewPayment base = request();
        return Stream.of(
                new NewPayment("ORDER-1002", base.amount(), base.currency(), base.description(), null, base.card()),
                new NewPayment(base.orderId(), 15001, base.currency(), base.description(), null, base.card()),
                new NewPayment(base.orderId(), base.amount(), Currency.USD, base.description(), null, base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), "Three tickets", null, base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), null, null, base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(), RETURN_URL,
                        base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(), null,
                        card("5555555555554444", 12, 2030, "123", "OLENA KOVALENKO")),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(), null,
                        card("4111111111111111", 11, 2030, "123", "OLENA KOVALENKO")),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(), null,
                        card("4111111111111111", 12, 2030, "123", "TARAS KOVALENKO")),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(), null,
                        card("4111111111111111", 12, 2030, "123", null)));
    }

    @ParameterizedTest
    @MethodSource("changedRequests")
    void shouldChangeWhenAnythingInRequestChanges(NewPayment changed) {
        assertThat(fingerprints.of(changed)).isNotEqualTo(fingerprints.of(request()));
    }

    @Test
    void shouldTellReturnUrlsApart() {
        NewPayment base = request();
        NewPayment withUrl = new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                RETURN_URL, base.card());
        NewPayment withOtherUrl = new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                URI.create("https://shop.example.com/checkout/other"), base.card());

        assertThat(fingerprints.of(withUrl)).isNotEqualTo(fingerprints.of(withOtherUrl));
    }

    @Test
    void shouldLeaveCvcOut() {
        NewPayment base = request();
        NewPayment otherCvc = new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(), null,
                card("4111111111111111", 12, 2030, "999", "OLENA KOVALENKO"));

        assertThat(fingerprints.of(otherCvc)).isEqualTo(fingerprints.of(base));
    }

    @Test
    void shouldKeepValuesApart() {
        // Joined without lengths, both would start with "ORDER-115000".
        NewPayment first = new NewPayment("ORDER-11", 5000, Currency.UAH, null, null, card());
        NewPayment second = new NewPayment("ORDER-1", 15000, Currency.UAH, null, null, card());

        assertThat(fingerprints.of(first)).isNotEqualTo(fingerprints.of(second));
    }

    @Test
    void shouldDependOnKey() {
        RequestFingerprints otherKey = new RequestFingerprints(new PaymentProperties(
                "another-fingerprint-key-0123456789ab", TestPayments.PUBLIC_URL, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 20));

        assertThat(otherKey.of(request())).isNotEqualTo(fingerprints.of(request()));
    }

    // risk-service counts a card's payments by its token, whatever else the payments say.
    @Test
    void shouldGiveCardSameTokenInEveryPayment() {
        String token = fingerprints.cardToken(card("4111111111111111", 12, 2030, "123", null).number());

        assertThat(fingerprints.cardToken(card("4111111111111111", 1, 2031, "999", "OLENA KOVALENKO").number()))
                .isEqualTo(token)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");
        assertThat(fingerprints.cardToken(card("5555555555554444", 12, 2030, "123", null).number()))
                .isNotEqualTo(token);
    }

    @Test
    void shouldMakeCardTokenWithKey() {
        RequestFingerprints otherKey = new RequestFingerprints(new PaymentProperties(
                "another-fingerprint-key-0123456789ab", TestPayments.PUBLIC_URL, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10), 20));

        assertThat(otherKey.cardToken(card().number())).isNotEqualTo(fingerprints.cardToken(card().number()));
    }

    @Test
    void shouldTellRefundRequestsApartByPaymentAndAmount() {
        UUID paymentId = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
        String fingerprint = fingerprints.ofRefund(paymentId, 5000);

        assertThat(fingerprints.ofRefund(paymentId, 5000)).isEqualTo(fingerprint).hasSize(64);
        assertThat(fingerprints.ofRefund(paymentId, 5001)).isNotEqualTo(fingerprint);
        assertThat(fingerprints.ofRefund(UUID.randomUUID(), 5000)).isNotEqualTo(fingerprint);
        assertThat(fingerprint).isNotEqualTo(fingerprints.of(request()));
    }
}
