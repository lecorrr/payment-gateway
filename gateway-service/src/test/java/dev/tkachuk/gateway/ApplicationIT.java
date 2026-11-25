package dev.tkachuk.gateway;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.gateway.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class ApplicationIT extends IntegrationTest {

    @Test
    void shouldReportHealthAndVersionWithoutApiKey() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.version").exists());
    }

    @Test
    void shouldServeContractWithoutApiKey() throws Exception {
        mockMvc.perform(get("/openapi/payment-gateway.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("operationId: createPayment")));
    }

    @Test
    void shouldAskForApiKeyOnAnyOtherPath() throws Exception {
        mockMvc.perform(get("/api/v1/refunds"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
