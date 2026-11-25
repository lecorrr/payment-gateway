package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"sim.api-key=" + ChargeApiTest.API_KEY, "sim.slow-delay=300ms"})
@AutoConfigureMockMvc
class ChargeApiTest {

    static final String API_KEY = "test-acquirer-key-0123456789";
    static final String RETURN_URL = "http://gateway.test/3ds/return/any";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldApproveTestCard() throws Exception {
        String reference = newReference();

        charge(reference, "4111111111111111", 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("ch_[0-9a-f]{24}")))
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.auth_code").value(matchesPattern("[0-9]{6}")))
                .andExpect(jsonPath("$.decline_code").doesNotExist())
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.created_at").isString());
    }

    @ParameterizedTest
    @CsvSource({"4000000000000002, do_not_honor", "4000000000009995, insufficient_funds"})
    void shouldDeclineTestCard(String number, String declineCode) throws Exception {
        charge(newReference(), number, 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("declined"))
                .andExpect(jsonPath("$.decline_code").value(declineCode))
                .andExpect(jsonPath("$.auth_code").doesNotExist());
    }

    @Test
    void shouldAnswerRepeatWithStoredCharge() throws Exception {
        String reference = newReference();
        String first = charge(reference, "4111111111111111", 15000)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        charge(reference, "4111111111111111", 15000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")));
    }

    @Test
    void shouldRefuseReferenceOfDifferentCharge() throws Exception {
        String reference = newReference();
        charge(reference, "4111111111111111", 15000).andExpect(status().isCreated());

        charge(reference, "4111111111111111", 99900)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("reference_reused"));
    }

    @Test
    void shouldFindChargeByReference() throws Exception {
        String reference = newReference();
        charge(reference, "4111111111111111", 15000).andExpect(status().isCreated());

        mockMvc.perform(get("/v1/charges").param("reference", reference).header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));
        mockMvc.perform(get("/v1/charges").param("reference", newReference())
                        .header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void shouldAskForChallengeOfChallengeCard() throws Exception {
        String reference = newReference();

        String body = charge(reference, Issuer.CHALLENGE_CARD, 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andExpect(jsonPath("$.action_url")
                        .value(matchesPattern("http://localhost:8091/challenge/ch_[0-9a-f]{24}")))
                .andExpect(jsonPath("$.auth_code").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/v1/charges").param("reference", reference).header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andExpect(jsonPath("$.action_url").value(JsonPath.<String>read(body, "$.action_url")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/return", "https://shop.example.com/a|b", "https:///no-host"})
    void shouldRefuseReturnUrlThatIsNoUrl(String returnUrl) throws Exception {
        mockMvc.perform(post("/v1/charges")
                        .header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chargeBody(newReference(), Issuer.CHALLENGE_CARD, 15000, returnUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                // "returnUrl" from bean validation, "return_url" from the URL check
                .andExpect(jsonPath("$.message").value(containsString("return")));
    }

    @Test
    void shouldAnswerSlowCardLate() throws Exception {
        long start = System.nanoTime();

        charge(newReference(), Issuer.SLOW_CARD, 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("approved"));

        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(300_000_000L);
    }

    @Test
    void shouldNeedApiKey() throws Exception {
        for (String authorization : new String[] {"Bearer wrong-key-0123456789", API_KEY}) {
            mockMvc.perform(post("/v1/charges")
                            .header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(chargeBody(newReference(), "4111111111111111", 15000)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }
        mockMvc.perform(get("/v1/charges").param("reference", "any")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNameInvalidFieldsWithoutTheirValues() throws Exception {
        mockMvc.perform(post("/v1/charges")
                        .header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chargeBody(newReference(), "4111-1111", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(containsString("card.number")))
                .andExpect(jsonPath("$.message").value(containsString("amount")))
                .andExpect(jsonPath("$.message").value(not(containsString("4111-1111"))));
    }

    private ResultActions charge(String reference, String number, long amount) throws Exception {
        return mockMvc.perform(post("/v1/charges")
                .header("Authorization", "Bearer " + API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(chargeBody(reference, number, amount)));
    }

    private static String chargeBody(String reference, String number, long amount) {
        return chargeBody(reference, number, amount, RETURN_URL);
    }

    private static String chargeBody(String reference, String number, long amount, String returnUrl) {
        return """
                {"reference": "%s", "amount": %d, "currency": "UAH",
                 "card": {"number": "%s", "exp_month": 12, "exp_year": 2099, "cvc": "123", "holder": "OLENA KOVALENKO"},
                 "description": "Two tickets", "return_url": "%s"}""".formatted(reference, amount, number, returnUrl);
    }

    private static String newReference() {
        return UUID.randomUUID().toString();
    }
}
