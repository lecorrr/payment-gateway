package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

/** The monobank-style API as the gateway's monobank connector uses it, webhooks included. */
// The same settings as ChargeApiTest, so both share one application context.
@SpringBootTest(properties = {"sim.api-key=" + ChargeApiTest.API_KEY, "sim.slow-delay=300ms",
        "sim.refund-delay=300ms"})
@AutoConfigureMockMvc
class MonobankApiTest {

    private static final String PAYMENT_DIRECT = "/api/merchant/invoice/payment-direct";
    private static final String STATUS = "/api/merchant/invoice/status";
    private static final String CANCEL = "/api/merchant/invoice/cancel";
    private static final String VISA = "4111111111111111";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MonobankKeys keys;

    @Test
    void shouldChargeCardAsMonobankAnswers() throws Exception {
        String reference = newReference();

        payDirect(reference, VISA, "123", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(matchesPattern("ch_[0-9a-f]{24}")))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.ccy").value(980))
                .andExpect(jsonPath("$.finalAmount").value(15000))
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.tdsUrl").doesNotExist());
    }

    // As monobank's, payment-direct gives the reason in words; the invoice status has the code.
    @ParameterizedTest
    @CsvSource({"4000000000000002, 123, 6", "4000000000009995, 123, 59", "4000000000000119, 123, 1000",
            "4111111111111111, 000, 80"})
    void shouldGiveFailuresErrCodeInStatusOnly(String pan, String cvv, String errCode) throws Exception {
        String invoiceId = invoiceIdOf(payDirect(newReference(), pan, cvv, null)
                .andExpect(jsonPath("$.status").value("failure"))
                .andExpect(jsonPath("$.failureReason").isString())
                .andExpect(jsonPath("$.errCode").doesNotExist()));

        invoiceStatus(invoiceId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failure"))
                .andExpect(jsonPath("$.errCode").value(errCode))
                .andExpect(jsonPath("$.finalAmount").value(0));
    }

    @Test
    void shouldSendCustomerToChallengeAndShowItsOutcome() throws Exception {
        String invoiceId = invoiceIdOf(payDirect(newReference(), Issuer.CHALLENGE_CARD, "123", null)
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.tdsUrl").value(startsWith("http://localhost:8091/challenge/ch_"))));
        invoiceStatus(invoiceId).andExpect(jsonPath("$.status").value("processing"));

        mockMvc.perform(post("/challenge/{id}", invoiceId).param("outcome", "pass"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", ChargeApiTest.RETURN_URL));

        invoiceStatus(invoiceId)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.finalAmount").value(15000));
    }

    @Test
    void shouldAnswerRepeatWithSameInvoice() throws Exception {
        String reference = newReference();
        String invoiceId = invoiceIdOf(payDirect(reference, VISA, "123", null));

        payDirect(reference, VISA, "123", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId));
    }

    @Test
    void shouldListInvoiceInStatementByItsReference() throws Exception {
        String reference = newReference();
        String invoiceId = invoiceIdOf(payDirect(reference, VISA, "123", null));

        mockMvc.perform(get("/api/merchant/statement")
                        .param("from", String.valueOf(Instant.now().minusSeconds(60).getEpochSecond()))
                        .header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.reference == '" + reference + "')].invoiceId").value(invoiceId))
                .andExpect(jsonPath("$.list[?(@.reference == '" + reference + "')].status").value("success"));
    }

    @Test
    void shouldReportInvoiceItDoesNotHave() throws Exception {
        invoiceStatus("ch_000000000000000000000000").andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong-acquirer-key-0123456"})
    void shouldNeedToken(String token) throws Exception {
        mockMvc.perform(post(PAYMENT_DIRECT)
                        .header(MonobankTokenFilter.HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentDirectBody(newReference(), VISA, "123", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/merchant/pubkey")).andExpect(status().isForbidden());
    }

    @Test
    void shouldRefuseCurrencyItDoesNotTake() throws Exception {
        String body = paymentDirectBody(newReference(), VISA, "123", null).replace("\"ccy\": 980", "\"ccy\": 643");

        mockMvc.perform(post(PAYMENT_DIRECT).header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void shouldRefuseHold() throws Exception {
        String body = paymentDirectBody(newReference(), VISA, "123", null).replace("\"debit\"", "\"hold\"");

        mockMvc.perform(post(PAYMENT_DIRECT).header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldHandOutKeyItSignsWith() throws Exception {
        String answer = mockMvc.perform(get("/api/merchant/pubkey")
                        .header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String key = JsonPath.read(answer, "$.key");
        assertThat(key).isEqualTo(keys.publicKeyBase64Pem());
        assertThat(new String(Base64.getDecoder().decode(key), StandardCharsets.US_ASCII))
                .startsWith("-----BEGIN PUBLIC KEY-----\n")
                .endsWith("\n-----END PUBLIC KEY-----\n");
    }

    @Test
    void shouldSendSignedWebhookOnceChargeIsDecided() throws Exception {
        try (WebhookReceiver receiver = WebhookReceiver.start()) {
            String reference = newReference();

            String invoiceId = invoiceIdOf(payDirect(reference, VISA, "123", receiver.url()));

            Received webhook = receiver.next();
            String body = new String(webhook.body(), StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(body, "$.invoiceId")).isEqualTo(invoiceId);
            assertThat((String) JsonPath.read(body, "$.reference")).isEqualTo(reference);
            assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("success");
            assertThat(signedByKeys(webhook)).isTrue();
        }
    }

    @Test
    void shouldSendWebhookWhenChallengeEnds() throws Exception {
        try (WebhookReceiver receiver = WebhookReceiver.start()) {
            String invoiceId = invoiceIdOf(payDirect(newReference(), Issuer.CHALLENGE_CARD, "123", receiver.url()));
            assertThat(receiver.nothingWithin(300)).as("no webhook while the challenge waits").isTrue();

            mockMvc.perform(post("/challenge/{id}", invoiceId).param("outcome", "fail"));

            Received webhook = receiver.next();
            String body = new String(webhook.body(), StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("failure");
            assertThat((String) JsonPath.read(body, "$.errCode")).isEqualTo("1054");
            assertThat(signedByKeys(webhook)).isTrue();
        }
    }

    @Test
    void shouldRefundInvoiceInPartAndListItInCancelList() throws Exception {
        String invoiceId = invoiceIdOf(payDirect(newReference(), VISA, "123", null));
        String extRef = newReference();

        cancel("{\"invoiceId\": \"%s\", \"extRef\": \"%s\", \"amount\": 5000}".formatted(invoiceId, extRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.createdDate").isString());
        invoiceStatus(invoiceId)
                .andExpect(jsonPath("$.cancelList[0].status").value("processing"))
                .andExpect(jsonPath("$.cancelList[0].extRef").value(extRef))
                .andExpect(jsonPath("$.cancelList[0].amount").value(5000))
                .andExpect(jsonPath("$.finalAmount").value(15000));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> invoiceStatus(invoiceId)
                .andExpect(jsonPath("$.cancelList[0].status").value("success")));
        invoiceStatus(invoiceId)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.finalAmount").value(10000));
    }

    @Test
    void shouldReverseInvoiceRefundedInFull() throws Exception {
        String invoiceId = invoiceIdOf(payDirect(newReference(), VISA, "123", null));

        cancel("{\"invoiceId\": \"%s\", \"extRef\": \"%s\"}".formatted(invoiceId, newReference()))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> invoiceStatus(invoiceId)
                .andExpect(jsonPath("$.status").value("reversed"))
                .andExpect(jsonPath("$.finalAmount").value(0))
                .andExpect(jsonPath("$.cancelList[0].amount").value(15000)));
        cancel("{\"invoiceId\": \"%s\"}".formatted(invoiceId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAnswerRepeatedCancelWithSameRefund() throws Exception {
        String invoiceId = invoiceIdOf(payDirect(newReference(), VISA, "123", null));
        String body = "{\"invoiceId\": \"%s\", \"extRef\": \"%s\", \"amount\": 5000}"
                .formatted(invoiceId, newReference());
        cancel(body).andExpect(status().isOk());

        cancel(body).andExpect(status().isOk());
        cancel(body.replace("\"amount\": 5000", "\"amount\": 6000")).andExpect(status().isBadRequest());

        invoiceStatus(invoiceId).andExpect(jsonPath("$.cancelList.length()").value(1));
    }

    @Test
    void shouldRefuseCancelThatCanNotBeDone() throws Exception {
        String invoiceId = invoiceIdOf(payDirect(newReference(), VISA, "123", null));
        String declined = invoiceIdOf(payDirect(newReference(), "4000000000000002", "123", null));

        cancel("{\"invoiceId\": \"%s\", \"amount\": 15001}".formatted(invoiceId))
                .andExpect(status().isBadRequest());
        cancel("{\"invoiceId\": \"%s\", \"amount\": 100}".formatted(declined))
                .andExpect(status().isBadRequest());
        cancel("{\"invoiceId\": \"ch_unknown\", \"amount\": 100}")
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldSendWebhookOnceRefundIsFinished() throws Exception {
        try (WebhookReceiver receiver = WebhookReceiver.start()) {
            String invoiceId = invoiceIdOf(payDirect(newReference(), Issuer.REFUND_DECLINED_CARD, "123",
                    receiver.url()));
            receiver.next();

            String extRef = newReference();
            cancel("{\"invoiceId\": \"%s\", \"extRef\": \"%s\", \"amount\": 5000}".formatted(invoiceId, extRef))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("processing"));

            Received webhook = receiver.next();
            String body = new String(webhook.body(), StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(body, "$.invoiceId")).isEqualTo(invoiceId);
            assertThat((String) JsonPath.read(body, "$.cancelList[0].status")).isEqualTo("failure");
            assertThat((String) JsonPath.read(body, "$.cancelList[0].extRef")).isEqualTo(extRef);
            assertThat(signedByKeys(webhook)).isTrue();
        }
    }

    private ResultActions cancel(String body) throws Exception {
        return mockMvc.perform(post(CANCEL)
                .header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions payDirect(String reference, String pan, String cvv, String webHookUrl) throws Exception {
        return mockMvc.perform(post(PAYMENT_DIRECT)
                .header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentDirectBody(reference, pan, cvv, webHookUrl)));
    }

    private ResultActions invoiceStatus(String invoiceId) throws Exception {
        return mockMvc.perform(get(STATUS).param("invoiceId", invoiceId)
                .header(MonobankTokenFilter.HEADER, ChargeApiTest.API_KEY));
    }

    private boolean signedByKeys(Received webhook) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(keys.publicKey());
        signature.update(webhook.body());
        return signature.verify(Base64.getDecoder().decode(webhook.signature()));
    }

    private static String invoiceIdOf(ResultActions answer) throws Exception {
        return JsonPath.read(answer.andReturn().getResponse().getContentAsString(), "$.invoiceId");
    }

    // As the gateway's PaymentDirectRequest sends it.
    private static String paymentDirectBody(String reference, String pan, String cvv, String webHookUrl) {
        String webHook = webHookUrl == null ? "" : "\"webHookUrl\": \"" + webHookUrl + "\", ";
        return """
                {"amount": 15000, "ccy": 980, "cardData": {"pan": "%s", "exp": "1299", "cvv": "%s"},
                 "merchantPaymInfo": {"reference": "%s", "destination": "Two tickets"},
                 "redirectUrl": "%s", %s"paymentType": "debit", "initiationKind": "client"}"""
                .formatted(pan, cvv, reference, ChargeApiTest.RETURN_URL, webHook);
    }

    // Charges and refunds each need a reference of their own: the tests share one application context, and with it
    // the simulator's stores, where a reference belongs to whichever request used it first.
    private static String newReference() {
        return UUID.randomUUID().toString();
    }

    private record Received(byte[] body, String signature) {
    }

    /** A webhook endpoint on a free local port that keeps what it gets and answers 200. */
    private static final class WebhookReceiver implements AutoCloseable {

        private final HttpServer server;
        private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

        private WebhookReceiver(HttpServer server) {
            this.server = server;
        }

        static WebhookReceiver start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            WebhookReceiver receiver = new WebhookReceiver(server);
            server.createContext("/hook", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String signature = exchange.getRequestHeaders().getFirst("X-Sign");
                // Answered before the test sees it: a test that has its webhook may stop the receiver at once.
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                receiver.received.add(new Received(body, signature));
            });
            server.start();
            return receiver;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        Received next() throws InterruptedException {
            Received webhook = received.poll(5, TimeUnit.SECONDS);
            assertThat(webhook).as("a webhook within 5 s").isNotNull();
            return webhook;
        }

        boolean nothingWithin(long millis) throws InterruptedException {
            return received.poll(millis, TimeUnit.MILLISECONDS) == null;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
