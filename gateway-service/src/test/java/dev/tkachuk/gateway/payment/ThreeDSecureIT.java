package dev.tkachuk.gateway.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 3-D Secure: the payment waits in REQUIRES_ACTION while the customer is at the bank's challenge, and is settled by
 * the customer's return to /3ds/return/{id}, or by the checker for a challenge left unfinished.
 */
class ThreeDSecureIT extends IntegrationTest {

    private static final String CHALLENGE_CARD = "4000 0000 0000 3220";
    private static final String ACTION_URL = "http://localhost:8091/challenge/ch_test_1";
    private static final String RETURN_URL = "https://shop.example.com/checkout/done?order=ORDER-1001";

    @Test
    void shouldSendCustomerToChallengeAndSettlePaymentOnReturn() throws Exception {
        acquirerAnswersCharge(challenge(201, ACTION_URL));
        String id = paymentId(createPayment("key-1", paymentRequest(CHALLENGE_CARD, RETURN_URL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUIRES_ACTION"))
                .andExpect(jsonPath("$.nextAction.type").value("REDIRECT"))
                .andExpect(jsonPath("$.nextAction.url").value(ACTION_URL))
                .andExpect(jsonPath("$.providerReference").value("ch_test_1")));
        acquirer.verify(postRequestedFor(urlEqualTo("/v1/charges"))
                .withRequestBody(matchingJsonPath("$.return_url", equalTo(PUBLIC_URL + "/3ds/return/" + id))));
        acquirerAnswersLookup(id, charge(200, "approved", null));

        mockMvc.perform(get("/3ds/return/{id}", id))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RETURN_URL + "&paymentId=" + id));

        getPayment(DEMO_API_KEY, id)
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nextAction").doesNotExist());
    }

    @Test
    void shouldShowStatusPageWhenPaymentHasNoReturnUrl() throws Exception {
        acquirerAnswersCharge(challenge(201, ACTION_URL));
        String id = waitingPaymentId(createPayment("key-1", paymentRequest(CHALLENGE_CARD)));
        acquirerAnswersLookup(id, charge(200, "declined", "authentication_failed"));

        mockMvc.perform(get("/3ds/return/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Payment DECLINED")));

        getPayment(DEMO_API_KEY, id)
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("authentication_failed"));
    }

    @Test
    void shouldSettleChallengeLeftUnfinishedFromChecker() throws Exception {
        acquirerAnswersCharge(challenge(201, ACTION_URL));
        String id = waitingPaymentId(createPayment("key-1", paymentRequest(CHALLENGE_CARD, RETURN_URL)));
        acquirerAnswersLookup(id, charge(200, "declined", "authentication_expired"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> getPayment(DEMO_API_KEY, id)
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("authentication_expired")));
    }

    // Past give-up-after too: an open challenge is no lost charge.
    @Test
    void shouldKeepWaitingWhileChallengeIsOpen() throws Exception {
        acquirerAnswersCharge(challenge(201, ACTION_URL));
        String id = waitingPaymentId(createPayment("key-1", paymentRequest(CHALLENGE_CARD, RETURN_URL)));
        acquirerAnswersLookup(id, challenge(200, ACTION_URL));

        mockMvc.perform(get("/3ds/return/{id}", id)).andExpect(status().isSeeOther());

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> getPayment(DEMO_API_KEY, id)
                        .andExpect(jsonPath("$.status").value("REQUIRES_ACTION"))
                        .andExpect(jsonPath("$.nextAction.url").value(ACTION_URL)));
    }

    @Test
    void shouldNotAskAcquirerAgainForSettledPayment() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA, RETURN_URL))
                .andExpect(jsonPath("$.status").value("SUCCEEDED")));

        mockMvc.perform(get("/3ds/return/{id}", id))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RETURN_URL + "&paymentId=" + id));

        acquirer.verify(0, getRequestedFor(urlPathEqualTo("/v1/charges")).withQueryParam("reference", equalTo(id)));
    }

    @Test
    void shouldAnswer404ForUnknownPaymentWithoutAskingForApiKey() throws Exception {
        mockMvc.perform(get("/3ds/return/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void shouldRefuseReturnUrlThatIsNoUrl() throws Exception {
        createPayment("key-1", paymentRequest(VISA, "https://shop.example.com/a|b"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("RETURN_URL_INVALID"));
        createPayment("key-2", paymentRequest(VISA, "shop.example.com/done"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations").value(hasItem(startsWith("returnUrl:"))));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    private static String paymentId(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String waitingPaymentId(ResultActions created) throws Exception {
        return paymentId(created.andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUIRES_ACTION")));
    }
}
