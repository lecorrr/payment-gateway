package dev.tkachuk.gateway.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.gateway.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** New payments go to acquirer-sim, then monobank: which provider charges, and when a payment moves on. */
class ProviderRoutingIT extends IntegrationTest {

    private static final String ACQUIRER_CHARGES = "/v1/charges";
    private static final String PAYMENT_DIRECT = "/api/merchant/invoice/payment-direct";

    @Test
    void shouldChargeWithNextProviderWhenFirstTurnsChargeAway() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(429));
        monobankApproves();

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("monobank"))
                .andExpect(jsonPath("$.providerReference").value("p2_test_1"));

        acquirer.verify(1, postRequestedFor(urlEqualTo(ACQUIRER_CHARGES)));
        monobank.verify(1, postRequestedFor(urlEqualTo(PAYMENT_DIRECT)));
    }

    // acquirer-sim may have charged the card, so the payment waits for its lookup instead of moving on.
    @Test
    void shouldKeepPaymentWithProviderWhoseAnswerWasLost() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(500));
        monobankApproves();

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.provider").value("acquirer-sim"));

        monobank.verify(0, postRequestedFor(urlEqualTo(PAYMENT_DIRECT)));
    }

    // A refused request reached acquirer-sim, which answered: nothing is wrong with the provider.
    @Test
    void shouldNotMovePaymentWhoseRequestWasRefused() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(400));
        monobankApproves();

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.declineCode").value("provider_rejected_request"))
                .andExpect(jsonPath("$.provider").value("acquirer-sim"));

        monobank.verify(0, postRequestedFor(urlEqualTo(PAYMENT_DIRECT)));
    }

    @Test
    void shouldSkipProviderWhoseCircuitOpenedAndTellOtherInstances() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(429));
        monobankApproves();
        createPayment("key-1", paymentRequest(VISA));
        createPayment("key-2", paymentRequest(VISA));

        createPayment("key-3", paymentRequest(VISA))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("monobank"));

        acquirer.verify(2, postRequestedFor(urlEqualTo(ACQUIRER_CHARGES)));
        String mark = RedisSharedCircuits.KEY_PREFIX + "acquirer-sim";
        assertThat(redis.hasKey(mark)).isTrue();
        assertThat(redis.getExpire(mark)).isBetween(1L, 10L);
    }

    @Test
    void shouldSkipProviderAnotherInstanceFoundFailing() throws Exception {
        failingElsewhere("acquirer-sim");
        monobankApproves();

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("monobank"));

        acquirer.verify(0, postRequestedFor(urlEqualTo(ACQUIRER_CHARGES)));
    }

    @Test
    void shouldFailPaymentNoProviderCouldTake() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(429));
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(aResponse().withStatus(429)));

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.declineCode").value("provider_unavailable"))
                .andExpect(jsonPath("$.provider").value("monobank"));
    }

    @Test
    void shouldFailPaymentAtOnceWhileEveryProviderIsFailing() throws Exception {
        failingElsewhere("acquirer-sim");
        failingElsewhere("monobank");

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.declineCode").value("provider_unavailable"));

        acquirer.verify(0, postRequestedFor(urlEqualTo(ACQUIRER_CHARGES)));
        monobank.verify(0, postRequestedFor(urlEqualTo(PAYMENT_DIRECT)));
    }

    private void monobankApproves() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"invoiceId\": \"p2_test_1\", \"status\": \"success\"}")));
    }

    // As another gateway instance marks a provider whose circuit it opened.
    private void failingElsewhere(String provider) {
        redis.opsForValue().set(RedisSharedCircuits.KEY_PREFIX + provider, "2026-11-25T10:00:00Z",
                Duration.ofSeconds(10));
    }
}
