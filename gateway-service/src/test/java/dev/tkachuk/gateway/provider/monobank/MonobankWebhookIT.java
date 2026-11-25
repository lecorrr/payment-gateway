package dev.tkachuk.gateway.provider.monobank;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * monobank's webhooks: signed with its key, and only a reason to look the payment up again. The payments are
 * written straight into the table, as new payments here go to acquirer-sim first; their next check is an hour away,
 * so whatever changes is the webhook's doing.
 */
class MonobankWebhookIT extends IntegrationTest {

    private static final String WEBHOOKS = "/providers/monobank/webhooks";
    private static final String INVOICE_ID = "p2_test_1";
    private static final String STATUS = "/api/merchant/invoice/status";
    private static final String STATEMENT = "/api/merchant/statement";

    @BeforeEach
    void serveMonobanksKey() {
        monobank.stubFor(get("/api/merchant/pubkey")
                .willReturn(json("{\"key\": \"" + MonobankKeys.TEST.publicKeyBase64Pem() + "\"}")));
    }

    @Test
    void shouldSettlePaymentWhenMonobankReportsChange() throws Exception {
        UUID paymentId = insertPayment("monobank", "REQUIRES_ACTION", INVOICE_ID);
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoice("success")));
        String body = report(paymentId, "success");

        mockMvc.perform(webhook(body).header("X-Sign", MonobankKeys.TEST.sign(body)))
                .andExpect(status().isOk());

        assertThat(payment(paymentId)).containsEntry("status", "SUCCEEDED")
                .containsEntry("provider_reference", INVOICE_ID);
    }

    // The report says success, but monobank's own answer counts: here it is still processing.
    @Test
    void shouldTakeMonobanksAnswerOverReport() throws Exception {
        UUID paymentId = insertPayment("monobank", "REQUIRES_ACTION", INVOICE_ID);
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(invoice("processing")));
        String body = report(paymentId, "success");

        mockMvc.perform(webhook(body).header("X-Sign", MonobankKeys.TEST.sign(body)))
                .andExpect(status().isOk());

        assertThat(payment(paymentId)).containsEntry("status", "REQUIRES_ACTION");
    }

    @Test
    void shouldRefuseReportNotSignedByMonobank() throws Exception {
        UUID paymentId = insertPayment("monobank", "REQUIRES_ACTION", INVOICE_ID);
        String body = report(paymentId, "success");

        mockMvc.perform(webhook(body).header("X-Sign", MonobankKeys.generate().sign(body)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(webhook(body)).andExpect(status().isBadRequest());

        assertThat(payment(paymentId)).containsEntry("status", "REQUIRES_ACTION");
        monobank.verify(0, getRequestedFor(urlPathEqualTo(STATUS)));
    }

    // The answer to the charge was lost: monobank's statement says which invoice it is, and the id is kept.
    @Test
    void shouldFindChargeWhoseAnswerWasLost() throws Exception {
        UUID paymentId = insertPayment("monobank", "PROCESSING", null);
        monobank.stubFor(get(urlPathEqualTo(STATEMENT)).willReturn(json("""
                {"list": [{"invoiceId": "%s", "status": "processing", "reference": "%s"}]}"""
                .formatted(INVOICE_ID, paymentId))));
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoice("processing")));
        String body = report(paymentId, "processing");

        mockMvc.perform(webhook(body).header("X-Sign", MonobankKeys.TEST.sign(body)))
                .andExpect(status().isOk());

        assertThat(payment(paymentId)).containsEntry("status", "PROCESSING")
                .containsEntry("provider_reference", INVOICE_ID);
    }

    @Test
    void shouldLeavePaymentOfAnotherProviderAlone() throws Exception {
        UUID paymentId = insertPayment("acquirer-sim", "PROCESSING", null);
        String body = report(paymentId, "success");

        mockMvc.perform(webhook(body).header("X-Sign", MonobankKeys.TEST.sign(body)))
                .andExpect(status().isOk());

        assertThat(payment(paymentId)).containsEntry("status", "PROCESSING");
        monobank.verify(0, getRequestedFor(urlPathEqualTo(STATUS)));
        acquirer.verify(0, getRequestedFor(urlPathEqualTo("/v1/charges"))
                .withQueryParam("reference", equalTo(paymentId.toString())));
    }

    // A signed report the gateway can't use still gets 200: monobank would only send it again.
    @Test
    void shouldAcceptReportAboutNoPaymentOfThisGateway() throws Exception {
        String body = """
                {"invoiceId": "p2_other", "status": "success", "reference": "ORDER-1001"}""";

        mockMvc.perform(webhook(body).header("X-Sign", MonobankKeys.TEST.sign(body)))
                .andExpect(status().isOk());

        monobank.verify(0, getRequestedFor(urlPathEqualTo(STATUS)));
    }

    private UUID insertPayment(String provider, String status, @Nullable String providerReference) {
        UUID id = UUID.randomUUID();
        String actionUrl = status.equals("REQUIRES_ACTION") ? "https://mbnk.test/3ds/" + providerReference : null;
        jdbc.update("""
                INSERT INTO payments (id, merchant_id, idempotency_key, request_fingerprint, order_id, amount,
                    currency, status, card_brand, card_bin, card_last4, provider, provider_reference, action_url,
                    next_check_at, created_at, updated_at)
                VALUES (?, (SELECT id FROM merchants WHERE code = 'demo-shop'), ?, ?, 'ORDER-1001', 15000, 'UAH', ?,
                    'VISA', '411111', '1111', ?, ?, ?, now() + interval '1 hour', now(), now())""",
                id, "key-" + id, "f".repeat(64), status, provider, providerReference, actionUrl);
        return id;
    }

    private Map<String, Object> payment(UUID id) {
        return jdbc.queryForMap("SELECT status, provider_reference FROM payments WHERE id = ?", id);
    }

    private static MockHttpServletRequestBuilder webhook(String body) {
        return post(WEBHOOKS).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // An invoice change as monobank reports it.
    private static String report(UUID paymentId, String status) {
        return """
                {"invoiceId": "%s", "status": "%s", "amount": 15000, "ccy": 980, "finalAmount": 15000,
                 "createdDate": "2026-11-25T10:00:00Z", "modifiedDate": "2026-11-25T10:00:05Z",
                 "reference": "%s", "cancelList": []}""".formatted(INVOICE_ID, status, paymentId);
    }

    private static ResponseDefinitionBuilder invoice(String status) {
        return json("""
                {"invoiceId": "%s", "status": "%s", "amount": 15000, "ccy": 980}""".formatted(INVOICE_ID, status));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
