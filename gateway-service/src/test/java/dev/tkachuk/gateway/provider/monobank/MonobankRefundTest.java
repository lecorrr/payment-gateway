package dev.tkachuk.gateway.provider.monobank;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderRefundResult;
import dev.tkachuk.gateway.provider.RefundLookup;
import dev.tkachuk.gateway.provider.RefundRequest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/** Refunds with monobank: invoice/cancel with the refund's id as extRef, and the invoice's cancelList for lookups. */
class MonobankRefundTest {

    private static final String TOKEN = "test-monobank-token";
    private static final String INVOICE_ID = "p2_test_1";
    private static final UUID REFUND_ID = UUID.fromString("5e2d1c0b-0000-4000-8000-000000000003");
    private static final String EXT_REF = "5e2d1c0b000040008000000000000003";
    private static final UUID PAYMENT_ID = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
    private static final RefundRequest REQUEST = new RefundRequest(REFUND_ID, PAYMENT_ID, INVOICE_ID, 5000,
            Currency.UAH);
    private static final RefundLookup LOOKUP = new RefundLookup(REFUND_ID, INVOICE_ID, null);
    private static final String CANCEL = "/api/merchant/invoice/cancel";
    private static final String STATUS = "/api/merchant/invoice/status";

    private static WireMockServer monobank;

    private MonobankConnector connector;

    @BeforeAll
    static void startMonobank() {
        monobank = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        monobank.start();
    }

    @AfterAll
    static void stopMonobank() {
        monobank.stop();
    }

    @BeforeEach
    void resetMonobank() {
        monobank.resetAll();
        MonobankProperties properties = new MonobankProperties(monobank.baseUrl(), TOKEN, "", Duration.ofMillis(300),
                Duration.ofMillis(700));
        connector = new MonobankConnector(new MonobankClient(RestClient.builder(), properties), properties);
    }

    @ParameterizedTest
    @CsvSource({"success, SUCCEEDED", "processing, PENDING"})
    void shouldSendRefundAsCancelOfInvoice(String status, ProviderRefundResult.Outcome outcome) {
        monobank.stubFor(post(CANCEL).willReturn(cancelAnswer(status)));

        assertThat(connector.refund(REQUEST).outcome()).isEqualTo(outcome);
        monobank.verify(postRequestedFor(urlEqualTo(CANCEL))
                .withHeader("X-Token", equalTo(TOKEN))
                .withRequestBody(equalToJson("""
                        {"invoiceId": "%s", "extRef": "%s", "amount": 5000}""".formatted(INVOICE_ID, EXT_REF))));
    }

    @Test
    void shouldNameMonobanksRefusalRefundDeclined() {
        monobank.stubFor(post(CANCEL).willReturn(cancelAnswer("failure")));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.failed(FailureCodes.REFUND_DECLINED));
    }

    @Test
    void shouldFailRefundMonobankTurnsAway() {
        monobank.stubFor(post(CANCEL).willReturn(aResponse().withStatus(429)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void shouldFailRefundMonobankRefusesToTake(int status) {
        monobank.stubFor(post(CANCEL).willReturn(aResponse().withStatus(status)));

        assertThat(connector.refund(REQUEST))
                .isEqualTo(ProviderRefundResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\": \"reversed\"}", "not json"})
    void shouldNotKnowUnusableAnswer(String body) {
        monobank.stubFor(post(CANCEL).willReturn(json(body)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.unknown());
    }

    @Test
    void shouldNotKnowAfterServerError() {
        monobank.stubFor(post(CANCEL).willReturn(aResponse().withStatus(500)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.unknown());
    }

    @ParameterizedTest
    @CsvSource({"success, SUCCEEDED", "processing, PENDING", "failure, FAILED"})
    void shouldFindRefundInCancelListByExtRef(String status, ProviderRefundResult.Outcome outcome) {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(json("""
                        {"invoiceId": "%s", "status": "success", "cancelList": [
                            {"status": "success", "amount": 1000, "ccy": 980, "extRef": "someoneelse"},
                            {"status": "%s", "amount": 5000, "ccy": 980, "extRef": "%s"}]}"""
                        .formatted(INVOICE_ID, status, EXT_REF))));

        assertThat(connector.lookupRefund(LOOKUP).outcome()).isEqualTo(outcome);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"cancelList\": []", "\"cancelList\": null", "\"finalAmount\": 15000"})
    void shouldReportRefundMissingFromCancelList(String field) {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(json("""
                {"invoiceId": "%s", "status": "success", %s}""".formatted(INVOICE_ID, field))));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.notFound());
    }

    @Test
    void shouldReportRefundOfInvoiceMonobankDoesNotHave() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(aResponse().withStatus(404)));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.notFound());
    }

    @Test
    void shouldNotKnowWhenRefundLookupFails() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(aResponse().withStatus(500)));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.unknown());
    }

    @Test
    void shouldNotKnowWhenInvoiceComesWithoutId() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(json("{\"status\": \"success\"}")));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.unknown());
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
