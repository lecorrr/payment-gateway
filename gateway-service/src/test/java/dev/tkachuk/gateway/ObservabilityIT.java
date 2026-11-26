package dev.tkachuk.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.gateway.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * What Prometheus scrapes from /actuator/prometheus once a payment went through: the gateway's own counters, the
 * providers' circuits, and the latency buckets of the API and of the calls to providers. No API key is needed.
 */
class ObservabilityIT extends IntegrationTest {

    @Test
    void shouldExposeMetricsForPrometheus() throws Exception {
        succeededPayment("pay-1");

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metrics)
                .containsPattern("gateway_payments_total\\{[^}]*status=\"SUCCEEDED\"[^}]*} [1-9]")
                .containsPattern("gateway_risk_checks_total\\{answer=\"allowed\"[^}]*}")
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"acquirer-sim\"[^}]*state=\"closed\"")
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"risk-service\"")
                .contains("http_server_requests_seconds_bucket")
                .contains("http_client_requests_seconds_bucket");
    }
}
