package dev.tkachuk.acquirer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** The challenge pages as a browser uses them: no API key, plain forms, and a redirect to the return URL. */
// The same settings as ChargeApiTest, so both share one application context.
@SpringBootTest(properties = {"sim.api-key=" + ChargeApiTest.API_KEY, "sim.slow-delay=300ms"})
@AutoConfigureMockMvc
class ChallengeApiTest {

    private static final String RETURN_URL = "http://gateway.test/3ds/return/any";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldShowChallengeWithBothButtons() throws Exception {
        String chargeId = challengeChargeId(newReference(), RETURN_URL);

        mockMvc.perform(get("/challenge/{id}", chargeId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("150.00 UAH")))
                .andExpect(content().string(containsString("action=\"/challenge/" + chargeId + "\"")))
                .andExpect(content().string(containsString("value=\"pass\"")))
                .andExpect(content().string(containsString("value=\"fail\"")));
    }

    @Test
    void shouldApprovePassedChallengeAndSendBrowserBack() throws Exception {
        String reference = newReference();
        String chargeId = challengeChargeId(reference, RETURN_URL);

        mockMvc.perform(post("/challenge/{id}", chargeId).param("outcome", "pass"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RETURN_URL));

        lookUp(reference)
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.action_url").doesNotExist());
    }

    @Test
    void shouldDeclineFailedChallengeAndSendBrowserBack() throws Exception {
        String reference = newReference();
        String chargeId = challengeChargeId(reference, RETURN_URL);

        mockMvc.perform(post("/challenge/{id}", chargeId).param("outcome", "fail"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RETURN_URL));

        lookUp(reference)
                .andExpect(jsonPath("$.status").value("declined"))
                .andExpect(jsonPath("$.decline_code").value("authentication_failed"));
    }

    @Test
    void shouldShowOutcomeOfFinishedChallenge() throws Exception {
        String chargeId = challengeChargeId(newReference(), RETURN_URL);
        mockMvc.perform(post("/challenge/{id}", chargeId).param("outcome", "pass"));

        mockMvc.perform(get("/challenge/{id}", chargeId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("the payment was approved")))
                .andExpect(content().string(containsString("href=\"" + RETURN_URL + "\"")))
                .andExpect(content().string(not(containsString("value=\"pass\""))));
    }

    @Test
    void shouldStayOnPageWithoutReturnUrl() throws Exception {
        String chargeId = challengeChargeId(newReference(), null);

        mockMvc.perform(post("/challenge/{id}", chargeId).param("outcome", "pass"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("the payment was approved")));
    }

    @Test
    void shouldRefuseOtherOutcome() throws Exception {
        String chargeId = challengeChargeId(newReference(), RETURN_URL);

        mockMvc.perform(post("/challenge/{id}", chargeId).param("outcome", "maybe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAnswer404ForUnknownOrMalformedId() throws Exception {
        mockMvc.perform(get("/challenge/{id}", "ch_000000000000000000000000")).andExpect(status().isNotFound());
        mockMvc.perform(get("/challenge/{id}", "<script>")).andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("<script>"))));
        mockMvc.perform(post("/challenge/{id}", "ch_000000000000000000000000").param("outcome", "pass"))
                .andExpect(status().isNotFound());
    }

    private String challengeChargeId(String reference, String returnUrl) throws Exception {
        String returnUrlJson = returnUrl == null ? "" : ", \"return_url\": \"" + returnUrl + "\"";
        String body = mockMvc.perform(post("/v1/charges")
                        .header("Authorization", "Bearer " + ChargeApiTest.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "%s", "amount": 15000, "currency": "UAH",
                                 "card": {"number": "%s", "exp_month": 12, "exp_year": 2099, "cvc": "123"}%s}"""
                                .formatted(reference, Issuer.CHALLENGE_CARD, returnUrlJson)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requires_action"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions lookUp(String reference) throws Exception {
        return mockMvc.perform(get("/v1/charges").param("reference", reference)
                .header("Authorization", "Bearer " + ChargeApiTest.API_KEY));
    }

    private static String newReference() {
        return UUID.randomUUID().toString();
    }
}
