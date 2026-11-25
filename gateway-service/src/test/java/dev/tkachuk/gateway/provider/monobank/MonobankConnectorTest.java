package dev.tkachuk.gateway.provider.monobank;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.card.Cvc;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.provider.ChargeLookup;
import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class MonobankConnectorTest {

    private static final String TOKEN = "test-monobank-token";
    private static final UUID REFERENCE = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
    private static final String RETURN_URL = "http://gateway.test/3ds/return/" + REFERENCE;
    private static final String INVOICE_ID = "p2_test_1";
    private static final String TDS_URL = "https://mbnk.test/3ds/p2_test_1";
    private static final Instant CHARGED_AT = Instant.parse("2026-11-25T10:00:00Z");
    private static final String PAYMENT_DIRECT = "/api/merchant/invoice/payment-direct";
    private static final String STATUS = "/api/merchant/invoice/status";
    private static final String STATEMENT = "/api/merchant/statement";

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
        connector = connectorFor(monobank.baseUrl(), "");
    }

    @Test
    void shouldSendChargeInMonobanksFormat() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("success", null, null)));

        ProviderResult result = connector.charge(chargeRequest());

        assertThat(result).isEqualTo(ProviderResult.approved(INVOICE_ID));
        monobank.verify(postRequestedFor(urlEqualTo(PAYMENT_DIRECT))
                .withHeader("X-Token", equalTo(TOKEN))
                .withRequestBody(equalToJson("""
                        {"amount": 15000, "ccy": 980,
                         "cardData": {"pan": "4111111111111111", "exp": "1230", "cvv": "123"},
                         "merchantPaymInfo": {"reference": "%s", "destination": "Two tickets"},
                         "redirectUrl": "%s", "paymentType": "debit", "initiationKind": "client"}"""
                        .formatted(REFERENCE, RETURN_URL))));
    }

    @Test
    void shouldAskForWebhooksWhenUrlIsSet() {
        String webhookUrl = "https://gateway.example.com/providers/monobank/webhooks";
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("success", null, null)));

        connectorFor(monobank.baseUrl(), webhookUrl).charge(chargeRequest());

        monobank.verify(postRequestedFor(urlEqualTo(PAYMENT_DIRECT))
                .withRequestBody(matchingJsonPath("$.webHookUrl", equalTo(webhookUrl))));
    }

    @Test
    void shouldSendCustomerToThreeDSecure() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("processing", null, TDS_URL)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.requiresAction(INVOICE_ID, TDS_URL));
    }

    @Test
    void shouldWaitForInvoiceStillProcessing() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("processing", null, null)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.pending(INVOICE_ID));
    }

    // No browser can be sent there, so the payment waits: the invoice expires if the customer never gets to 3DS.
    @ParameterizedTest
    @ValueSource(strings = {"/3ds/p2_test_1", "javascript:alert(1)", "https:///3ds"})
    void shouldWaitWhenThreeDSecureUrlIsUnusable(String tdsUrl) {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("processing", null, tdsUrl)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.pending(INVOICE_ID));
    }

    @Test
    void shouldAskInvoiceStatusWhyChargeFailed() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("failure", null, null)));
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoice("failure", "59", null)));

        assertThat(connector.charge(chargeRequest()))
                .isEqualTo(ProviderResult.declined(INVOICE_ID, "insufficient_funds"));
    }

    @Test
    void shouldTakeErrCodeFromChargeAnswerWhenItHasOne() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("failure", "80", null)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.declined(INVOICE_ID, "incorrect_cvc"));
        monobank.verify(0, getRequestedFor(urlPathEqualTo(STATUS)));
    }

    @Test
    void shouldDeclineAsDoNotHonorWhenReasonCannotBeFetched() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("failure", null, null)));
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(aResponse().withStatus(500)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.declined(INVOICE_ID, "do_not_honor"));
    }

    // 403 is also what a token without card payments by card details gets.
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405})
    void shouldFailChargeWhoseRequestWasRefused(int status) {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(errorAnswer(status)));

        assertThat(connector.charge(chargeRequest()))
                .isEqualTo(ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST));
    }

    @Test
    void shouldFailChargeTurnedAwayForTooManyRequests() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(errorAnswer(429)));

        assertThat(connector.charge(chargeRequest()))
                .isEqualTo(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void shouldNotKnowWhenMonobankFailsOnCharge(int status) {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(errorAnswer(status)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldNotKnowWhenAnswerIsTooLate() {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(invoice("success", null, null).withFixedDelay(1500)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldFailChargeWhenMonobankIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        ProviderResult result = connectorFor("http://127.0.0.1:" + closedPort, "").charge(chargeRequest());

        assertThat(result).isEqualTo(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"status\": \"success\"}", "{\"invoiceId\": \"p2_test_1\"}"})
    void shouldNotKnowChargeAnswerWithoutInvoiceIdOrStatus(String body) {
        monobank.stubFor(post(PAYMENT_DIRECT).willReturn(json(200, body)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldLookUpInvoiceById() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoice("success", null, null)));

        assertThat(connector.lookup(lookupOf(INVOICE_ID))).isEqualTo(ProviderResult.approved(INVOICE_ID));
        monobank.verify(getRequestedFor(urlPathEqualTo(STATUS)).withHeader("X-Token", equalTo(TOKEN)));
    }

    @Test
    void shouldLookUpDeclineWithItsCode() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(invoice("failure", "1054", null)));

        assertThat(connector.lookup(lookupOf(INVOICE_ID)))
                .isEqualTo(ProviderResult.declined(INVOICE_ID, "authentication_failed"));
    }

    // An invoice expires when the customer never finishes 3-D Secure.
    @Test
    void shouldDeclineExpiredInvoiceAsExpiredAuthentication() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(invoice("expired", null, null)));

        assertThat(connector.lookup(lookupOf(INVOICE_ID)))
                .isEqualTo(ProviderResult.declined(INVOICE_ID, "authentication_expired"));
    }

    // hold and reversed can't follow the gateway's debits; the payment waits for someone to look.
    @ParameterizedTest
    @ValueSource(strings = {"created", "processing", "hold", "reversed", "something_new"})
    void shouldWaitForInvoiceWithoutOutcome(String status) {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(invoice(status, null, null)));

        assertThat(connector.lookup(lookupOf(INVOICE_ID))).isEqualTo(ProviderResult.pending(INVOICE_ID));
    }

    @Test
    void shouldReportInvoiceMonobankDoesNotHave() {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(errorAnswer(404)));

        assertThat(connector.lookup(lookupOf(INVOICE_ID))).isEqualTo(ProviderResult.notFound());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 429, 500})
    void shouldNotKnowWhenLookupFails(int status) {
        monobank.stubFor(get(urlPathEqualTo(STATUS)).willReturn(errorAnswer(status)));

        assertThat(connector.lookup(lookupOf(INVOICE_ID))).isEqualTo(ProviderResult.unknown());
    }

    // The answer to the charge was lost, so the gateway has no invoice id: the statement since the charge has it.
    @Test
    void shouldFindChargeWhoseAnswerWasLostInStatement() {
        String from = String.valueOf(CHARGED_AT.minusSeconds(60).getEpochSecond());
        monobank.stubFor(get(urlPathEqualTo(STATEMENT)).withQueryParam("from", equalTo(from)).willReturn(json(200, """
                {"list": [
                  {"invoiceId": "p2_other", "status": "success", "reference": "not-this-one", "amount": 100},
                  {"invoiceId": "%s", "status": "success", "reference": "%s", "amount": 15000}]}"""
                .formatted(INVOICE_ID, REFERENCE))));
        monobank.stubFor(get(urlPathEqualTo(STATUS)).withQueryParam("invoiceId", equalTo(INVOICE_ID))
                .willReturn(invoice("success", null, null)));

        assertThat(connector.lookup(lookupOf(null))).isEqualTo(ProviderResult.approved(INVOICE_ID));
    }

    @Test
    void shouldReportChargeMissingFromStatement() {
        monobank.stubFor(get(urlPathEqualTo(STATEMENT)).willReturn(json(200, """
                {"list": [{"invoiceId": "p2_other", "status": "success", "reference": "not-this-one"}]}""")));

        assertThat(connector.lookup(lookupOf(null))).isEqualTo(ProviderResult.notFound());
        monobank.verify(0, getRequestedFor(urlPathEqualTo(STATUS)));
    }

    // A statement that can't be read says nothing about the charge, so the payment is not given up on its account.
    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500})
    void shouldNotKnowWhenStatementFails(int status) {
        monobank.stubFor(get(urlPathEqualTo(STATEMENT)).willReturn(errorAnswer(status)));

        assertThat(connector.lookup(lookupOf(null))).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldNotKnowWhenStatementHasNoList() {
        monobank.stubFor(get(urlPathEqualTo(STATEMENT)).willReturn(json(200, "{}")));

        assertThat(connector.lookup(lookupOf(null))).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldNeedTokenToCharge() {
        MonobankProperties withoutToken = new MonobankProperties(monobank.baseUrl(), "", "", Duration.ofMillis(300),
                Duration.ofMillis(700));
        MonobankConnector unconfigured = new MonobankConnector(new MonobankClient(RestClient.builder(), withoutToken),
                withoutToken);

        assertThat(unconfigured.missingSettings()).singleElement()
                .satisfies(setting -> assertThat(setting).contains("GATEWAY_MONOBANK_TOKEN"));
        assertThat(connector.missingSettings()).isEmpty();
    }

    private static MonobankConnector connectorFor(String baseUrl, String webhookUrl) {
        MonobankProperties properties = new MonobankProperties(baseUrl, TOKEN, webhookUrl, Duration.ofMillis(300),
                Duration.ofMillis(700));
        return new MonobankConnector(new MonobankClient(RestClient.builder(), properties), properties);
    }

    private static ChargeRequest chargeRequest() {
        Card card = new Card(CardNumber.parse("4111111111111111"), CardExpiry.of(12, 2030), new Cvc("123"),
                "OLENA KOVALENKO");
        return new ChargeRequest(REFERENCE, 15000, Currency.UAH, card, "Two tickets", URI.create(RETURN_URL));
    }

    private static ChargeLookup lookupOf(@Nullable String invoiceId) {
        return new ChargeLookup(REFERENCE, invoiceId, CHARGED_AT);
    }

    // An invoice as monobank answers it, with fields the gateway doesn't read.
    private static ResponseDefinitionBuilder invoice(String status, @Nullable String errCode,
            @Nullable String tdsUrl) {
        return json(200, """
                {"invoiceId": "%s", "status": "%s", "amount": 15000, "ccy": 980, "finalAmount": 15000,
                 "createdDate": "2026-11-25T10:00:00Z", "modifiedDate": "2026-11-25T10:00:01Z",
                 "reference": "%s", "errCode": %s, "failureReason": %s, "tdsUrl": %s}"""
                .formatted(INVOICE_ID, status, REFERENCE, quoted(errCode), errCode == null ? "null" : "\"Declined\"",
                        quoted(tdsUrl)));
    }

    private static ResponseDefinitionBuilder errorAnswer(int status) {
        return json(status, "{\"errCode\": \"ERROR\", \"errText\": \"something is wrong\"}");
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String quoted(@Nullable String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
