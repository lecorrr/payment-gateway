package dev.tkachuk.gateway.provider.acquirersim;

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
import com.github.tomakehurst.wiremock.http.Fault;
import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.card.Cvc;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class AcquirerSimConnectorTest {

    private static final String API_KEY = "test-acquirer-key-0123456789";
    private static final UUID REFERENCE = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");

    private static WireMockServer acquirer;

    private AcquirerSimConnector connector;

    @BeforeAll
    static void startAcquirer() {
        acquirer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        acquirer.start();
    }

    @AfterAll
    static void stopAcquirer() {
        acquirer.stop();
    }

    @BeforeEach
    void resetAcquirer() {
        acquirer.resetAll();
        connector = connectorFor(acquirer.baseUrl());
    }

    @Test
    void shouldSendChargeInAcquirersFormat() {
        acquirer.stubFor(post("/v1/charges").willReturn(charge(201, "approved", null)));

        ProviderResult result = connector.charge(chargeRequest());

        assertThat(result).isEqualTo(ProviderResult.approved("ch_test_1"));
        acquirer.verify(postRequestedFor(urlEqualTo("/v1/charges"))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(equalToJson("""
                        {"reference": "7f1c2b3a-0000-4000-8000-000000000001", "amount": 15000, "currency": "UAH",
                         "card": {"number": "4111111111111111", "exp_month": 12, "exp_year": 2030, "cvc": "123",
                                  "holder": "OLENA KOVALENKO"},
                         "description": "Two tickets"}""")));
    }

    @Test
    void shouldPassOnAcquirersDeclineCode() {
        acquirer.stubFor(post("/v1/charges").willReturn(charge(201, "declined", "insufficient_funds")));

        assertThat(connector.charge(chargeRequest()))
                .isEqualTo(ProviderResult.declined("ch_test_1", "insufficient_funds"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 409, 422})
    void shouldFailChargeWhoseRequestWasRefused(int status) {
        acquirer.stubFor(post("/v1/charges").willReturn(aResponse().withStatus(status)));

        assertThat(connector.charge(chargeRequest()))
                .isEqualTo(ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST));
    }

    @Test
    void shouldFailChargeWhenAcquirerIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        ProviderResult result = connectorFor("http://127.0.0.1:" + closedPort).charge(chargeRequest());

        assertThat(result).isEqualTo(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void shouldNotKnowAfterServerError(int status) {
        acquirer.stubFor(post("/v1/charges").willReturn(aResponse().withStatus(status)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldNotKnowAfterReadTimeout() {
        acquirer.stubFor(post("/v1/charges").willReturn(charge(201, "approved", null).withFixedDelay(2000)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldNotKnowAfterDroppedConnectionAndNotSendAgain() {
        acquirer.stubFor(post("/v1/charges").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"status\": \"approved\"}",
            "{\"id\": \"ch_test_1\", \"status\": \"pending\"}"})
    void shouldNotKnowAfterAnswerItCannotUse(String body) {
        acquirer.stubFor(post("/v1/charges").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));

        assertThat(connector.charge(chargeRequest())).isEqualTo(ProviderResult.unknown());
    }

    @Test
    void shouldLookUpChargeByReference() {
        acquirer.stubFor(get(urlPathEqualTo("/v1/charges"))
                .withQueryParam("reference", equalTo(REFERENCE.toString()))
                .willReturn(charge(200, "approved", null)));

        assertThat(connector.lookup(REFERENCE)).isEqualTo(ProviderResult.approved("ch_test_1"));
    }

    @Test
    void shouldReportChargeAcquirerDoesNotHave() {
        acquirer.stubFor(get(urlPathEqualTo("/v1/charges")).willReturn(aResponse().withStatus(404)));

        assertThat(connector.lookup(REFERENCE)).isEqualTo(ProviderResult.notFound());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 503})
    void shouldNotKnowWhenLookupFails(int status) {
        acquirer.stubFor(get(urlPathEqualTo("/v1/charges")).willReturn(aResponse().withStatus(status)));

        assertThat(connector.lookup(REFERENCE)).isEqualTo(ProviderResult.unknown());
    }

    private static AcquirerSimConnector connectorFor(String baseUrl) {
        return new AcquirerSimConnector(RestClient.builder(),
                new AcquirerSimProperties(baseUrl, API_KEY, Duration.ofMillis(300), Duration.ofMillis(700)));
    }

    private static ChargeRequest chargeRequest() {
        Card card = new Card(CardNumber.parse("4111111111111111"), CardExpiry.of(12, 2030), new Cvc("123"),
                "OLENA KOVALENKO");
        return new ChargeRequest(REFERENCE, 15000, Currency.UAH, card, "Two tickets");
    }

    // A charge as acquirer-sim answers it, with fields the gateway doesn't read.
    private static ResponseDefinitionBuilder charge(int status, String chargeStatus, String declineCode) {
        String declineCodeJson = declineCode == null ? "null" : "\"" + declineCode + "\"";
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "ch_test_1", "reference": "%s", "status": "%s", "decline_code": %s,
                         "auth_code": "654321", "amount": 15000, "currency": "UAH",
                         "created_at": "2026-11-25T10:00:00Z"}"""
                        .formatted(REFERENCE, chargeStatus, declineCodeJson));
    }
}
