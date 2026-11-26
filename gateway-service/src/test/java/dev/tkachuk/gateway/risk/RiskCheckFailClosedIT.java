package dev.tkachuk.gateway.risk;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.gateway.support.IntegrationTest;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With gateway.risk.when-unavailable=decline, a payment risk-service can't check is not charged. Its own context. */
@TestPropertySource(properties = "gateway.risk.when-unavailable=decline")
class RiskCheckFailClosedIT extends IntegrationTest {

    @Test
    void shouldFailPaymentRiskServiceCannotCheck() throws Exception {
        riskService.failWith(Status.UNAVAILABLE);

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.declineCode").value("risk_unavailable"));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/charges")));
    }
}
