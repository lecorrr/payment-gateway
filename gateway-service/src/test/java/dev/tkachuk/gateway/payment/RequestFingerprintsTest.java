package dev.tkachuk.gateway.payment;

import static dev.tkachuk.gateway.payment.TestPayments.card;
import static dev.tkachuk.gateway.payment.TestPayments.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.gateway.common.Currency;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RequestFingerprintsTest {

    private final RequestFingerprints fingerprints = new RequestFingerprints(TestPayments.properties());

    @Test
    void shouldGiveSameFingerprintForSameRequest() {
        assertThat(fingerprints.of(request())).isEqualTo(fingerprints.of(request())).hasSize(64);
    }

    static Stream<NewPayment> changedRequests() {
        NewPayment base = request();
        return Stream.of(
                new NewPayment("ORDER-1002", base.amount(), base.currency(), base.description(), base.card()),
                new NewPayment(base.orderId(), 15001, base.currency(), base.description(), base.card()),
                new NewPayment(base.orderId(), base.amount(), Currency.USD, base.description(), base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), "Three tickets", base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), null, base.card()),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                        card("5555555555554444", 12, 2030, "123", "OLENA KOVALENKO")),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                        card("4111111111111111", 11, 2030, "123", "OLENA KOVALENKO")),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                        card("4111111111111111", 12, 2030, "123", "TARAS KOVALENKO")),
                new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                        card("4111111111111111", 12, 2030, "123", null)));
    }

    @ParameterizedTest
    @MethodSource("changedRequests")
    void shouldChangeWhenAnythingInRequestChanges(NewPayment changed) {
        assertThat(fingerprints.of(changed)).isNotEqualTo(fingerprints.of(request()));
    }

    @Test
    void shouldLeaveCvcOut() {
        NewPayment base = request();
        NewPayment otherCvc = new NewPayment(base.orderId(), base.amount(), base.currency(), base.description(),
                card("4111111111111111", 12, 2030, "999", "OLENA KOVALENKO"));

        assertThat(fingerprints.of(otherCvc)).isEqualTo(fingerprints.of(base));
    }

    @Test
    void shouldKeepValuesApart() {
        // Joined without lengths, both would start with "ORDER-115000".
        NewPayment first = new NewPayment("ORDER-11", 5000, Currency.UAH, null, card());
        NewPayment second = new NewPayment("ORDER-1", 15000, Currency.UAH, null, card());

        assertThat(fingerprints.of(first)).isNotEqualTo(fingerprints.of(second));
    }

    @Test
    void shouldDependOnKey() {
        RequestFingerprints otherKey = new RequestFingerprints(new PaymentProperties(
                "another-fingerprint-key-0123456789ab", Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofMinutes(10), 20));

        assertThat(otherKey.of(request())).isNotEqualTo(fingerprints.of(request()));
    }
}
