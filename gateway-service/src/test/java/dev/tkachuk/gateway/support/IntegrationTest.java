package dev.tkachuk.gateway.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The service against PostgreSQL and Redis in Testcontainers, with WireMock as acquirer-sim, as monobank and as a
 * merchant's webhook endpoint, and short timings: the providers time out after 700 ms, a payment is first looked up
 * after 1 s and given up after 2 s, a challenge is looked up every 300 ms, and a webhook is retried twice, 200 ms
 * apart. New payments go to acquirer-sim, and to monobank when acquirer-sim is unavailable; a provider's circuit
 * opens for 10 s once half of its last charges (at least 2, at most 4) failed. risk-service is FakeRiskService, over
 * in-process gRPC, and allows every payment until a test says otherwise. All integration tests share this one
 * context, so they share its containers too. Every test starts with no payments, no webhook events, only the demo
 * merchant, which has no webhook endpoint, a risk-service that allows everything, and every circuit closed.
 */
@SpringBootTest(properties = {
        "gateway.demo-merchant.api-key=" + IntegrationTest.DEMO_API_KEY,
        "gateway.payments.fingerprint-key=test-fingerprint-key-0123456789abcdef",
        "gateway.payments.public-url=" + IntegrationTest.PUBLIC_URL,
        "gateway.payments.first-check-after=1s",
        "gateway.payments.check-interval=200ms",
        "gateway.payments.challenge-check-interval=300ms",
        "gateway.payments.give-up-after=2s",
        "gateway.providers.order=acquirer-sim,monobank",
        "gateway.providers.circuit-breaker.window-size=4",
        "gateway.providers.circuit-breaker.minimum-calls=2",
        "gateway.providers.circuit-breaker.failure-rate-percent=50",
        "gateway.providers.circuit-breaker.open-for=10s",
        "gateway.providers.circuit-breaker.trial-calls=1",
        "gateway.providers.acquirer-sim.api-key=test-acquirer-key-0123456789",
        "gateway.providers.acquirer-sim.connect-timeout=300ms",
        "gateway.providers.acquirer-sim.read-timeout=700ms",
        "gateway.providers.monobank.token=" + IntegrationTest.MONOBANK_TOKEN,
        "gateway.providers.monobank.connect-timeout=300ms",
        "gateway.providers.monobank.read-timeout=700ms",
        "gateway.webhooks.dispatch-interval=100ms",
        "gateway.webhooks.connect-timeout=300ms",
        "gateway.webhooks.read-timeout=1s",
        "gateway.webhooks.retry-delays=200ms,200ms"})
@AutoConfigureMockMvc
@AutoConfigureTestGrpcTransport
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, AcquirerStub.class, MonobankStub.class,
        MerchantEndpointStub.class, FakeRiskService.class})
public abstract class IntegrationTest {

    protected static final String DEMO_API_KEY = "test-demo-merchant-key-0123456789";
    protected static final String PUBLIC_URL = "http://gateway.test";
    protected static final String MONOBANK_TOKEN = "test-monobank-token";
    protected static final String VISA = "4111 1111 1111 1111";
    protected static final String MASTERCARD = "5555 5555 5555 4444";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    @Qualifier("acquirer")
    protected WireMockServer acquirer;

    @Autowired
    @Qualifier("monobank")
    protected WireMockServer monobank;

    @Autowired
    @Qualifier("merchantEndpoint")
    protected WireMockServer merchantEndpoint;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakers;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected FakeRiskService riskService;

    @Autowired
    @Qualifier("riskServiceCircuit")
    protected CircuitBreaker riskServiceCircuit;

    // The tables first, so the checker and the dispatcher have nothing left to send by the time the stubs are reset.
    // Circuits before Redis: a circuit that closes removes its mark there.
    @BeforeEach
    void startClean() {
        jdbc.execute("TRUNCATE webhook_events, payments");
        jdbc.update("DELETE FROM webhook_endpoints");
        jdbc.update("DELETE FROM merchants WHERE code <> 'demo-shop'");
        acquirer.resetAll();
        monobank.resetAll();
        merchantEndpoint.resetAll();
        riskService.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        riskServiceCircuit.reset();
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
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

    /** A request whose customer's browser goes to returnUrl after a 3-D Secure challenge. */
    protected static String paymentRequest(String cardNumber, String returnUrl) {
        return """
                {"orderId": "ORDER-1001", "amount": 15000, "currency": "UAH", "description": "Two tickets",
                 "returnUrl": "%s",
                 "card": {"number": "%s", "expiryMonth": 12, "expiryYear": 2099, "cvc": "123",
                          "holder": "OLENA KOVALENKO"}}""".formatted(returnUrl, cardNumber);
    }

    protected void acquirerAnswersCharge(ResponseDefinitionBuilder answer) {
        acquirer.stubFor(post("/v1/charges").willReturn(answer));
    }

    protected void acquirerAnswersLookup(String paymentId, ResponseDefinitionBuilder answer) {
        acquirer.stubFor(WireMock.get(urlPathEqualTo("/v1/charges"))
                .withQueryParam("reference", equalTo(paymentId))
                .willReturn(answer));
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

    /** A charge waiting for the cardholder's 3-D Secure challenge at actionUrl, as acquirer-sim answers it. */
    protected static ResponseDefinitionBuilder challenge(int status, String actionUrl) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "ch_test_1", "reference": "any", "status": "requires_action", "action_url": "%s",
                         "amount": 15000, "currency": "UAH", "created_at": "2026-11-25T10:00:00Z"}"""
                        .formatted(actionUrl));
    }
}
