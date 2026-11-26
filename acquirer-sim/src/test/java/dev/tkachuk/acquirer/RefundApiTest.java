package dev.tkachuk.acquirer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"sim.api-key=" + ChargeApiTest.API_KEY, "sim.slow-delay=300ms"})
@AutoConfigureMockMvc
class RefundApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRefundChargeByItsId() throws Exception {
        String chargeId = chargeId("4111111111111111");
        String reference = UUID.randomUUID().toString();

        refund(chargeId, reference, 5000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.charge_id").value(chargeId))
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.failure_code").doesNotExist());

        mockMvc.perform(get("/v1/refunds").param("reference", reference).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"));
    }

    @Test
    void shouldAnswerRepeatWithStoredRefund() throws Exception {
        String chargeId = chargeId("4111111111111111");
        String reference = UUID.randomUUID().toString();
        String id = JsonPath.read(refund(chargeId, reference, 5000).andReturn().getResponse().getContentAsString(),
                "$.id");

        refund(chargeId, reference, 5000).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        refund(chargeId, reference, 4000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("reference_reused"));
    }

    @Test
    void shouldRefuseMoreThanIsLeftOfCharge() throws Exception {
        String chargeId = chargeId("4111111111111111");
        refund(chargeId, UUID.randomUUID().toString(), 10000).andExpect(status().isCreated());

        refund(chargeId, UUID.randomUUID().toString(), 5001)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("amount_too_large"));
    }

    @Test
    void shouldRefundOnlyApprovedChargeItHas() throws Exception {
        refund(chargeId("4000000000000002"), UUID.randomUUID().toString(), 5000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("charge_not_refundable"));
        refund("ch_unknown", UUID.randomUUID().toString(), 5000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get("/v1/refunds").param("reference", "nothing").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldDeclineRefundOfCardThatRefusesRefunds() throws Exception {
        refund(chargeId(Issuer.REFUND_DECLINED_CARD), UUID.randomUUID().toString(), 5000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("refund_declined"));
    }

    @Test
    void shouldRefuseRequestWithoutAmount() throws Exception {
        mockMvc.perform(post("/v1/refunds")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\": \"ch_1\", \"reference\": \"refund-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("amount: must not be null"));
    }

    @Test
    void shouldNeedApiKey() throws Exception {
        mockMvc.perform(post("/v1/refunds").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private String chargeId(String cardNumber) throws Exception {
        String body = mockMvc.perform(post("/v1/charges")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "amount": 15000, "currency": "UAH",
                                 "card": {"number": "%s", "exp_month": 12, "exp_year": 2030, "cvc": "123"}}"""
                                .formatted(UUID.randomUUID(), cardNumber)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions refund(String chargeId, String reference, long amount) throws Exception {
        return mockMvc.perform(post("/v1/refunds")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"charge_id": "%s", "reference": "%s", "amount": %d}""".formatted(chargeId, reference,
                        amount)));
    }

    private static String bearer() {
        return "Bearer " + ChargeApiTest.API_KEY;
    }
}
