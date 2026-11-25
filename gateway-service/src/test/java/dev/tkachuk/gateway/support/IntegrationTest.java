package dev.tkachuk.gateway.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The service against PostgreSQL in Testcontainers and WireMock as acquirer-sim, with short timings: the acquirer
 * times out after 700 ms, a payment is first looked up after 1 s and given up after 2 s. All integration tests share
 * this one context, so they share its containers too. Every test starts with no payments and only the demo merchant.
 */
@SpringBootTest(properties = {
        "gateway.demo-merchant.api-key=" + IntegrationTest.DEMO_API_KEY,
        "gateway.payments.fingerprint-key=test-fingerprint-key-0123456789abcdef",
        "gateway.payments.first-check-after=1s",
        "gateway.payments.check-interval=200ms",
        "gateway.payments.give-up-after=2s",
        "gateway.providers.acquirer-sim.api-key=test-acquirer-key-0123456789",
        "gateway.providers.acquirer-sim.connect-timeout=300ms",
        "gateway.providers.acquirer-sim.read-timeout=700ms"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, AcquirerStub.class})
public abstract class IntegrationTest {

    protected static final String DEMO_API_KEY = "test-demo-merchant-key-0123456789";
    protected static final String VISA = "4111 1111 1111 1111";
    protected static final String MASTERCARD = "5555 5555 5555 4444";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected WireMockServer acquirer;

    @BeforeEach
    void startClean() {
        acquirer.resetAll();
        jdbc.execute("TRUNCATE payments");
        jdbc.update("DELETE FROM merchants WHERE code <> 'demo-shop'");
    }

    protected ResultActions createPayment(String apiKey, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                .header("X-Api-Key", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected ResultActions createPayment(String idempotencyKey, String body) throws Exception {
        return createPayment(DEMO_API_KEY, idempotencyKey, body);
    }

    protected ResultActions getPayment(String apiKey, String paymentId) throws Exception {
        return mockMvc.perform(get("/api/v1/payments/{id}", paymentId).header("X-Api-Key", apiKey));
    }

    protected static String paymentRequest(String cardNumber) {
        return paymentRequest(cardNumber, 2099);
    }

    protected static String paymentRequest(String cardNumber, int expiryYear) {
        return """
                {"orderId": "ORDER-1001", "amount": 15000, "currency": "UAH", "description": "Two tickets",
                 "card": {"number": "%s", "expiryMonth": 12, "expiryYear": %d, "cvc": "123",
                          "holder": "OLENA KOVALENKO"}}""".formatted(cardNumber, expiryYear);
    }

    protected void acquirerAnswersCharge(ResponseDefinitionBuilder answer) {
        acquirer.stubFor(post("/v1/charges").willReturn(answer));
    }

    /** A charge as acquirer-sim answers it; declineCode may be null. */
    protected static ResponseDefinitionBuilder charge(int status, String chargeStatus, String declineCode) {
        String declineCodeJson = declineCode == null ? "null" : "\"" + declineCode + "\"";
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "ch_test_1", "reference": "any", "status": "%s", "decline_code": %s,
                         "auth_code": "654321", "amount": 15000, "currency": "UAH",
                         "created_at": "2026-11-25T10:00:00Z"}"""
                        .formatted(chargeStatus, declineCodeJson));
    }
}
