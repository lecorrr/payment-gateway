package dev.tkachuk.gateway.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

@ExtendWith(OutputCaptureExtension.class)
class CreatePaymentIT extends IntegrationTest {

    @Test
    void shouldChargeCardAndKeepOnlyItsSummary(CapturedOutput output) throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));

        String location = createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.orderId").value("ORDER-1001"))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.description").value("Two tickets"))
                .andExpect(jsonPath("$.card.brand").value("VISA"))
                .andExpect(jsonPath("$.card.bin").value("411111"))
                .andExpect(jsonPath("$.card.last4").value("1111"))
                .andExpect(jsonPath("$.card.number").doesNotExist())
                .andExpect(jsonPath("$.provider").value("acquirer-sim"))
                .andExpect(jsonPath("$.providerReference").value("ch_test_1"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("/api/v1/payments/");
        getPayment(DEMO_API_KEY, location.substring("/api/v1/payments/".length()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        String storedRows = jdbc.queryForObject("SELECT string_agg(p::text, ' ') FROM payments p", String.class);
        assertThat(storedRows).contains("411111", "1111").doesNotContain("4111111111111111", "OLENA");
        assertThat(output.getAll()).doesNotContain("4111111111111111", VISA, "OLENA");
    }

    @Test
    void shouldPassOnDecline() throws Exception {
        acquirerAnswersCharge(charge(201, "declined", "insufficient_funds"));

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("insufficient_funds"));
    }

    @Test
    void shouldFailPaymentWhenAcquirerRefusesRequest() throws Exception {
        acquirerAnswersCharge(aResponse().withStatus(400));

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.declineCode").value("provider_rejected_request"));
    }

    @Test
    void shouldReplaySameRequestWithoutChargingAgain() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));
        String first = createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void shouldRefuseSameKeyWithDifferentRequest() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));
        createPayment("key-1", paymentRequest(VISA)).andExpect(status().isCreated());

        createPayment("key-1", paymentRequest(MASTERCARD))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void shouldRefuseCardThatFailsLuhnCheckWithoutCallingAcquirer() throws Exception {
        createPayment("key-1", paymentRequest("4111 1111 1111 1112"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CARD_INVALID"));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/charges")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
    }

    @Test
    void shouldRefuseExpiredCard() throws Exception {
        createPayment("key-1", paymentRequest(VISA, 2020))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CARD_EXPIRED"));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void shouldNameMissingFields() throws Exception {
        createPayment("key-1", """
                {"card": {"number": "4111 1111 1111 1111", "expiryMonth": 12, "expiryYear": 2099, "cvc": "123"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").value(hasItem(startsWith("orderId:"))))
                .andExpect(jsonPath("$.violations").value(hasItem(startsWith("amount:"))))
                .andExpect(jsonPath("$.violations").value(hasItem(startsWith("currency:"))));
    }

    @Test
    void shouldNameBadCardFieldWithoutRepeatingIt() throws Exception {
        createPayment("key-1", paymentRequest("4111-1111-1111-1111"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").value(hasItem(startsWith("card.number:"))))
                .andExpect(content().string(not(containsString("4111-1111"))));
    }

    @Test
    void shouldRefuseUnknownCurrencyAndBrokenJson() throws Exception {
        createPayment("key-1", paymentRequest(VISA).replace("\"UAH\"", "\"GBP\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        createPayment("key-1", "{\"orderId\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void shouldRequireIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", DEMO_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentRequest(VISA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0]").value("Idempotency-Key: is required"));
    }

    @Test
    void shouldLimitIdempotencyKeyLength() throws Exception {
        createPayment("k".repeat(101), paymentRequest(VISA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0]").value(startsWith("idempotencyKey:")));
    }

    @Test
    void shouldAnswer404ForUnknownPaymentAnd400ForBadId() throws Exception {
        getPayment(DEMO_API_KEY, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        getPayment(DEMO_API_KEY, "not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
