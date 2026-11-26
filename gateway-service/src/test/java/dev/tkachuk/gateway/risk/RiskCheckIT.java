package dev.tkachuk.gateway.risk;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import dev.tkachuk.risk.api.AssessRequest;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

/** The risk check in the payment's way, over gRPC to FakeRiskService. */
class RiskCheckIT extends IntegrationTest {

    @Test
    void shouldChargePaymentRiskServiceAllows() throws Exception {
        acquirerAnswersCharge(charge(201, "approved", null));

        String body = createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(riskService.requests()).hasSize(1);
        AssessRequest asked = riskService.requests().getFirst();
        assertThat(asked.getPaymentId()).isEqualTo(JsonPath.read(body, "$.id"));
        assertThat(asked.getAttemptId()).endsWith("/key-1");
        assertThat(asked.getAmount()).isEqualTo(15000);
        assertThat(asked.getCurrency()).isEqualTo("UAH");
        assertThat(asked.getCardBin()).isEqualTo("411111");
        assertThat(asked.getCardToken()).hasSize(43);
        assertThat(asked.toString()).doesNotContain("4111111111111111");
    }

    @Test
    void shouldDeclinePaymentRiskServiceDeclinesWithoutChargingIt() throws Exception {
        riskService.decline("too_many_payments");

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("risk_declined"))
                .andExpect(jsonPath("$.provider").value("acquirer-sim"));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void shouldReplayRefusedPaymentWithoutAskingAgain() throws Exception {
        riskService.decline("blocked_card_range");
        createPayment("key-1", paymentRequest(VISA));

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.declineCode").value("risk_declined"));

        assertThat(riskService.requests()).hasSize(1);
    }

    // gateway.risk.when-unavailable is allow here; RiskCheckFailClosedIT has decline.
    @Test
    void shouldChargePaymentRiskServiceCannotCheck() throws Exception {
        riskService.failWith(Status.UNAVAILABLE);
        acquirerAnswersCharge(charge(201, "approved", null));

        createPayment("key-1", paymentRequest(VISA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }
}
