package dev.tkachuk.gateway.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Webhooks from the outbox to the demo merchant's endpoint, a WireMock here: signed, retried with the same
 * webhook-id, given up after the last retry, and never redirected.
 */
class WebhookDeliveryIT extends IntegrationTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @BeforeEach
    void giveDemoMerchantEndpoint() {
        jdbc.update("""
                INSERT INTO webhook_endpoints (merchant_id, url, secret, created_at, updated_at)
                SELECT id, ?, ?, now(), now() FROM merchants WHERE code = 'demo-shop'""",
                merchantEndpoint.baseUrl() + "/webhooks", SECRET);
    }

    @Test
    void shouldSendSignedEventWhenPaymentSucceeds() throws Exception {
        merchantEndpoint.stubFor(post("/webhooks").willReturn(aResponse().withStatus(204)));
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("DELIVERED"));

        List<LoggedRequest> requests = webhooksReceived();
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertSignedWithSecret(request);
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        String body = request.getBodyAsString();
        assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("payment.succeeded");
        assertThat(JsonPath.<String>read(body, "$.data.id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(body, "$.data.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<String>read(body, "$.data.card.last4")).isEqualTo("1111");
        assertThat(body).doesNotContain("4111111111111111", "OLENA");
    }

    @Test
    void shouldRetryWithSameIdUntilDelivered() throws Exception {
        merchantEndpoint.stubFor(post("/webhooks").inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        merchantEndpoint.stubFor(post("/webhooks").inScenario("flaky").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200)));
        acquirerAnswersCharge(charge(201, "declined", "do_not_honor"));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("DELIVERED"));

        List<LoggedRequest> requests = webhooksReceived();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).getHeader("webhook-id")).isEqualTo(requests.get(0).getHeader("webhook-id"));
        assertSignedWithSecret(requests.get(1));
        assertThat(event(id)).containsEntry("attempts", 2).containsEntry("last_error", null);
    }

    @Test
    void shouldGiveUpAfterLastRetry() throws Exception {
        merchantEndpoint.stubFor(post("/webhooks").willReturn(aResponse().withStatus(500)));
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("FAILED"));

        assertThat(webhooksReceived()).hasSize(3);
        assertThat(event(id)).containsEntry("attempts", 3).containsEntry("last_error", "HTTP 500");
    }

    @Test
    void shouldNotFollowRedirect() throws Exception {
        merchantEndpoint.stubFor(post("/webhooks")
                .willReturn(aResponse().withStatus(307).withHeader("Location", "/moved")));
        merchantEndpoint.stubFor(post("/moved").willReturn(aResponse().withStatus(204)));
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("FAILED"));

        assertThat(event(id)).containsEntry("last_error", "HTTP 307");
        merchantEndpoint.verify(0, postRequestedFor(urlEqualTo("/moved")));
    }

    @Test
    void shouldRetryWhenEndpointRefusesConnection() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        jdbc.update("UPDATE webhook_endpoints SET url = ?", "http://127.0.0.1:" + closedPort + "/webhooks");
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("FAILED"));

        Map<String, Object> event = event(id);
        assertThat(event).containsEntry("attempts", 3);
        assertThat((String) event.get("last_error")).startsWith("ConnectException");
    }

    @Test
    void shouldStopRightAwayWhenEndpointIsGone() throws Exception {
        merchantEndpoint.stubFor(post("/webhooks").willReturn(aResponse().withStatus(410)));
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("FAILED"));

        assertThat(webhooksReceived()).hasSize(1);
        assertThat(event(id)).containsEntry("attempts", 1).containsEntry("last_error", "HTTP 410");
    }

    // The return and the checker may both settle the payment; only one of them writes the change, and its event.
    @Test
    void shouldReportChallengeAndItsOutcomeOnce() throws Exception {
        merchantEndpoint.stubFor(post("/webhooks").willReturn(aResponse().withStatus(204)));
        acquirerAnswersCharge(challenge(201, "http://localhost:8091/challenge/ch_test_1"));
        String id = paymentId(createPayment("key-1", paymentRequest("4000 0000 0000 3220"))
                .andExpect(jsonPath("$.status").value("REQUIRES_ACTION")));
        acquirerAnswersLookup(id, charge(200, "approved", null));

        mockMvc.perform(get("/3ds/return/{id}", id)).andExpect(status().isOk());

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT type FROM webhook_events WHERE payment_id = ? AND status = 'DELIVERED' ORDER BY created_at",
                String.class, UUID.fromString(id))).containsExactly("payment.requires_action", "payment.succeeded"));
    }

    @Test
    void shouldStoreNoEventForMerchantWithoutEndpoint() throws Exception {
        jdbc.update("DELETE FROM webhook_endpoints");
        acquirerAnswersCharge(charge(201, "approved", null));

        createPayment("key-1", paymentRequest(VISA)).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events", Integer.class)).isZero();
    }

    // An event left from before the merchant's endpoint was removed, as when the gateway restarts without the demo
    // merchant's webhook settings: it is given up without an attempt.
    @Test
    void shouldGiveUpEventWhoseEndpointWasRemoved() throws Exception {
        jdbc.update("DELETE FROM webhook_endpoints");
        acquirerAnswersCharge(charge(201, "approved", null));
        String id = paymentId(createPayment("key-1", paymentRequest(VISA)));
        jdbc.update("""
                INSERT INTO webhook_events (id, merchant_id, payment_id, type, payload, status, attempts,
                    next_attempt_at, created_at)
                SELECT ?, merchant_id, id, 'payment.succeeded', '{}', 'PENDING', 0, now(), now()
                FROM payments WHERE id = ?""", UUID.randomUUID(), UUID.fromString(id));

        await().atMost(WAIT).untilAsserted(() -> assertThat(eventStatuses(id)).containsExactly("FAILED"));

        assertThat(event(id)).containsEntry("attempts", 0)
                .containsEntry("last_error", "The merchant has no webhook endpoint any more");
        assertThat(webhooksReceived()).isEmpty();
    }

    // Checked here without the gateway's signing code, the way a merchant would check it.
    private static void assertSignedWithSecret(LoggedRequest request) throws Exception {
        String messageId = request.getHeader("webhook-id");
        String timestamp = request.getHeader("webhook-timestamp");
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        String expected = "v1," + Base64.getEncoder().encodeToString(mac.doFinal(request.getBody()));

        assertThat(request.getHeader("webhook-signature")).isEqualTo(expected);
        assertThat(Long.parseLong(timestamp)).isCloseTo(Instant.now().getEpochSecond(), within(60L));
    }

    private List<LoggedRequest> webhooksReceived() {
        return merchantEndpoint.findAll(postRequestedFor(urlEqualTo("/webhooks")));
    }

    private List<String> eventStatuses(String paymentId) {
        return jdbc.queryForList("SELECT status FROM webhook_events WHERE payment_id = ?", String.class,
                UUID.fromString(paymentId));
    }

    private Map<String, Object> event(String paymentId) {
        return jdbc.queryForMap("SELECT attempts, last_error FROM webhook_events WHERE payment_id = ?",
                UUID.fromString(paymentId));
    }

    private static String paymentId(ResultActions created) throws Exception {
        return JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.id");
    }
}
