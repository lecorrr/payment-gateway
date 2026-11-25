package dev.tkachuk.gateway.merchant;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class MerchantAccessIT extends IntegrationTest {

    private static final String OTHER_API_KEY = "test-other-merchant-key-0123456789";

    @BeforeEach
    void addOtherMerchant() {
        jdbc.update("""
                INSERT INTO merchants (id, code, name, api_key_hash, created_at)
                VALUES (?, 'other-shop', 'Other Shop', ?, now())""", UUID.randomUUID(), ApiKeys.hash(OTHER_API_KEY));
    }

    @Test
    void shouldRefuseRequestWithoutValidApiKey() throws Exception {
        for (String apiKey : new String[] {"", "unknown-key-0123456789abcdef"}) {
            createPayment(apiKey, "key-1", paymentRequest(VISA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string("WWW-Authenticate", "ApiKey"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    void shouldShowMerchantOnlyItsOwnPayments() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));
        String body = createPayment("key-1", paymentRequest(VISA)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        getPayment(OTHER_API_KEY, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        getPayment(DEMO_API_KEY, id).andExpect(status().isOk());
    }

    @Test
    void shouldKeepIdempotencyKeysPerMerchant() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));
        String first = createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.id");

        createPayment(OTHER_API_KEY, "key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(firstId)));
    }
}
