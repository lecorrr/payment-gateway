package dev.tkachuk.gateway.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A charge whose answer is lost, because the acquirer is slower than the 700 ms read timeout, stays PROCESSING until
 * a lookup settles it.
 */
class PendingPaymentsIT extends IntegrationTest {

    private static final int SLOWER_THAN_READ_TIMEOUT_MS = 3000;

    @Test
    void shouldFinishTimedOutChargeFromLookup() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null).withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS));
        String id = createProcessingPayment();
        acquirer.stubFor(get(urlPathEqualTo("/v1/charges"))
                .withQueryParam("reference", equalTo(id))
                .willReturn(charge(200, "approved", null)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> getPayment(DEMO_API_KEY, id)
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerReference").value("ch_test_1")));
        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void shouldFailChargeAcquirerNeverRecorded() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(201).withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS));
        String id = createProcessingPayment();
        acquirer.stubFor(get(urlPathEqualTo("/v1/charges")).willReturn(aResponse().withStatus(404)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> getPayment(DEMO_API_KEY, id)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.declineCode").value("provider_no_record")));
    }

    @Test
    void shouldKeepPaymentProcessingWhileAcquirerCannotAnswer() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(201).withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS));
        String id = createProcessingPayment();
        acquirer.stubFor(get(urlPathEqualTo("/v1/charges")).willReturn(aResponse().withStatus(503)));

        // Past give-up-after (2 s): a lookup that fails says nothing about the charge, so the gateway keeps asking.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> getPayment(DEMO_API_KEY, id).andExpect(jsonPath("$.status").value("PROCESSING")));
        acquirer.verify(moreThanOrExactly(2), getRequestedFor(urlPathEqualTo("/v1/charges")));
    }

    private String createProcessingPayment() throws Exception {
        String body = createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
