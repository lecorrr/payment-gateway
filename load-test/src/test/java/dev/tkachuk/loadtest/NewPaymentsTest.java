package dev.tkachuk.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NewPaymentsTest {

    @Test
    void shouldSendTheGatewaysPaymentRequestWithANewCard() {
        NewPayments payments = new NewPayments("run1", new TestCards(0));

        Map<String, Object> first = payments.next();

        String card = "411111000000000" + TestCards.luhnDigit("411111000000000");
        assertThat(first).containsEntry(NewPayments.IDEMPOTENCY_KEY, "load-run1-1");
        assertThat(first.get(NewPayments.BODY)).isEqualTo("{\"orderId\": \"LOAD-run1-1\", \"amount\": 15000, "
                + "\"currency\": \"UAH\", \"card\": {\"number\": \"" + card + "\", \"expiryMonth\": 12, "
                + "\"expiryYear\": 2030, \"cvc\": \"123\"}}");
    }

    @Test
    void shouldGiveEachPaymentItsOwnKeyOrderAndCard() {
        NewPayments payments = new NewPayments("run1", new TestCards(0));

        Map<String, Object> first = payments.next();
        Map<String, Object> second = payments.next();

        assertThat(second.get(NewPayments.IDEMPOTENCY_KEY)).isEqualTo("load-run1-2");
        assertThat((String) second.get(NewPayments.BODY)).contains("\"orderId\": \"LOAD-run1-2\"")
                .doesNotContain(cardOf(first));
        assertThat(payments.hasNext()).isTrue();
    }

    private static String cardOf(Map<String, Object> payment) {
        String body = (String) payment.get(NewPayments.BODY);
        int start = body.indexOf("\"number\": \"") + "\"number\": \"".length();
        return body.substring(start, start + 16);
    }
}
