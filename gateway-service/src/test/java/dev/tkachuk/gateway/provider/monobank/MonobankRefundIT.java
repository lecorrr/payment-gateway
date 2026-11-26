package dev.tkachuk.gateway.provider.monobank;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Refunds of payments monobank charged, through invoice/cancel: monobank mostly answers "processing", and then
 * either its webhook or the checker finds the refund's outcome in the invoice's cancelList, by its extRef. The
 * payments are written straight into the table as SUCCEEDED with invoice p2_test_1, since new payments here go to
 * acquirer-sim first.
 */
class MonobankRefundIT extends IntegrationTest {

    private static final String INVOICE_ID = "p2_test_1";
    private static final String CANCEL = "/api/merchant/invoice/cancel";
    private static final String STATUS = "/api/merchant/invoice/status";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @BeforeEach
    void serveMonobanksKey() {
        monobank.stubFor(get("/api/merchant/pubkey")
                .willReturn(json("{\"key\": \"" + MonobankKeys.TEST.publicKeyBase64Pem() + "\"}")));
    }

    @Test
    void shouldFinishRefundWhenMonobankReportsIt() throws Exception {
        UUID paymentId = insertSucceededPayment();
        monobank.stubFor(post(CANCEL).willReturn(cancelAnswer("processing")));

        String refundId = refundId(createRefund(paymentId.toString(), "refund-1", 5000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.provider").value("monobank")));
        String extRef = refundId.replace("-", "");
        monobank.verify(postRequestedFor(urlPathEqualTo(CANCEL)).withRequestBody(equalToJson("""
                {"invoiceId": "%s", "extRef": "%s", "amount": 5000}""".formatted(INVOICE_ID, extRef))));

        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoiceWithCancel(extRef, "success")));
        String report = report(paymentId, extRef, "success");
        mockMvc.perform(MockMvcRequestBuilders.post("/providers/monobank/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report)
                        .header("X-Sign", MonobankKeys.TEST.sign(report)))
                .andExpect(status().isOk());

        getRefund(paymentId.toString(), refundId).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        getPayment(DEMO_API_KEY, paymentId.toString()).andExpect(jsonPath("$.refundedAmount").value(5000));
    }

    // No webhook comes: the checker looks the refund up in the invoice's cancelList.
    @Test
    void shouldFindRefundsOutcomeWithoutWebhook() throws Exception {
        UUID paymentId = insertSucceededPayment();
        monobank.stubFor(post(CANCEL).willReturn(cancelAnswer("processing")));
        String refundId = refundId(createRefund(paymentId.toString(), "refund-1", 15000));
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoiceWithCancel(refundId.replace("-", ""), "failure")));

        await().atMost(WAIT).untilAsserted(() -> getRefund(paymentId.toString(), refundId)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("refund_declined")));
        getPayment(DEMO_API_KEY, paymentId.toString()).andExpect(jsonPath("$.refundedAmount").value(0));
    }

    @Test
    void shouldTakeRefundMonobankFinishesAtOnce() throws Exception {
        UUID paymentId = insertSucceededPayment();
        monobank.stubFor(post(CANCEL).willReturn(cancelAnswer("success")));

        createRefund(paymentId.toString(), "refund-1", 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        getPayment(DEMO_API_KEY, paymentId.toString()).andExpect(jsonPath("$.refundedAmount").value(15000));
    }

    // A 400 is monobank refusing the request itself, so nothing was refunded.
    @Test
    void shouldFailRefundMonobankRefusesToTake() throws Exception {
        UUID paymentId = insertSucceededPayment();
        monobank.stubFor(post(CANCEL).willReturn(aResponse().withStatus(400)));

        createRefund(paymentId.toString(), "refund-1", 5000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("provider_rejected_request"));
    }

    private UUID insertSucceededPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments (id, merchant_id, idempotency_key, request_fingerprint, order_id, amount,
                    currency, status, card_brand, card_bin, card_last4, provider, provider_reference,
                    created_at, updated_at)
                VALUES (?, (SELECT id FROM merchants WHERE code = 'demo-shop'), ?, ?, 'ORDER-1001', 15000, 'UAH',
                    'SUCCEEDED', 'VISA', '411111', '1111', 'monobank', ?, now(), now())""",
                id, "key-" + id, "f".repeat(64), INVOICE_ID);
        return id;
    }

    private static String refundId(ResultActions answer) throws Exception {
        return JsonPath.read(answer.andReturn().getResponse().getContentAsString(), "$.id");
    }

    // An invoice change as monobank reports it, with one refund in its cancelList.
    private static String report(UUID paymentId, String extRef, String cancelStatus) {
        return """
                {"invoiceId": "%s", "status": "success", "amount": 15000, "ccy": 980, "finalAmount": 10000,
                 "reference": "%s", "cancelList": [{"status": "%s", "amount": 5000, "ccy": 980, "extRef": "%s"}]}"""
                .formatted(INVOICE_ID, paymentId, cancelStatus, extRef);
    }

    private static ResponseDefinitionBuilder invoiceWithCancel(String extRef, String cancelStatus) {
        return json("""
                {"invoiceId": "%s", "status": "success", "amount": 15000, "ccy": 980,
                 "cancelList": [{"status": "%s", "amount": 5000, "ccy": 980, "extRef": "%s"}]}"""
                .formatted(INVOICE_ID, cancelStatus, extRef));
    }

    private static ResponseDefinitionBuilder cancelAnswer(String status) {
        return json("""
                {"status": "%s", "createdDate": "2026-11-25T10:01:00Z", "modifiedDate": "2026-11-25T10:01:00Z"}"""
                .formatted(status));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
