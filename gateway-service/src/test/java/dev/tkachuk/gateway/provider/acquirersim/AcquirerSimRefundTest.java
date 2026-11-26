package dev.tkachuk.gateway.provider.acquirersim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderRefundResult;
import dev.tkachuk.gateway.provider.RefundLookup;
import dev.tkachuk.gateway.provider.RefundRequest;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/** Refunds with acquirer-sim: "not refunded" told from "don't know" by the same rules as charges. */
class AcquirerSimRefundTest {

    private static final String API_KEY = "test-acquirer-key-0123456789";
    private static final UUID REFUND_ID = UUID.fromString("5e2d1c0b-0000-4000-8000-000000000003");
    private static final UUID PAYMENT_ID = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
    private static final RefundRequest REQUEST = new RefundRequest(REFUND_ID, PAYMENT_ID, "ch_test_1", 5000,
            Currency.UAH);
    private static final RefundLookup LOOKUP = new RefundLookup(REFUND_ID, "ch_test_1", null);

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
    void shouldSendRefundInAcquirersFormat() {
        acquirer.stubFor(post("/v1/refunds").willReturn(refund(201, "succeeded", null)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.succeeded("re_test_1"));
        acquirer.verify(postRequestedFor(urlEqualTo("/v1/refunds"))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(equalToJson("""
                        {"charge_id": "ch_test_1", "reference": "%s", "amount": 5000}""".formatted(REFUND_ID))));
    }

    @Test
    void shouldKeepRefundAcquirerIsStillWorkingOn() {
        acquirer.stubFor(post("/v1/refunds").willReturn(refund(201, "pending", null)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.pending("re_test_1"));
    }

    @Test
    void shouldPassOnRefusalWithItsCode() {
        acquirer.stubFor(post("/v1/refunds").willReturn(refund(201, "failed", "charge_too_old")));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.failed("re_test_1", "charge_too_old"));
    }

    @Test
    void shouldNameRefusalWithoutCodeRefundDeclined() {
        acquirer.stubFor(post("/v1/refunds").willReturn(refund(201, "failed", null)));

        assertThat(connector.refund(REQUEST))
                .isEqualTo(ProviderRefundResult.failed("re_test_1", FailureCodes.REFUND_DECLINED));
    }

    @Test
    void shouldFailRefundAcquirerTurnsAway() {
        acquirer.stubFor(post("/v1/refunds").willReturn(aResponse().withStatus(429)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void shouldFailRefundAcquirerRefusesToTake(int status) {
        acquirer.stubFor(post("/v1/refunds").willReturn(aResponse().withStatus(status)));

        assertThat(connector.refund(REQUEST))
                .isEqualTo(ProviderRefundResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST));
    }

    @Test
    void shouldFailRefundThatNeverLeft() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        ProviderRefundResult result = connectorFor("http://127.0.0.1:" + closedPort).refund(REQUEST);

        assertThat(result).isEqualTo(ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
    }

    @Test
    void shouldNotKnowAfterServerError() {
        acquirer.stubFor(post("/v1/refunds").willReturn(aResponse().withStatus(503)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.unknown());
    }

    @Test
    void shouldNotKnowWhenAnswerIsLate() {
        acquirer.stubFor(post("/v1/refunds").willReturn(refund(201, "succeeded", null).withFixedDelay(1500)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.unknown());
    }

    @Test
    void shouldNotKnowWhenConnectionDrops() {
        acquirer.stubFor(post("/v1/refunds").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.unknown());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\": \"succeeded\"}", "{\"id\": \"re_test_1\", \"status\": \"reversed\"}",
            "not json"})
    void shouldNotKnowUnusableAnswer(String body) {
        acquirer.stubFor(post("/v1/refunds").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(body)));

        assertThat(connector.refund(REQUEST)).isEqualTo(ProviderRefundResult.unknown());
    }

    @Test
    void shouldLookUpRefundByItsReference() {
        acquirer.stubFor(get(urlPathEqualTo("/v1/refunds")).willReturn(refund(200, "succeeded", null)));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.succeeded("re_test_1"));
        acquirer.verify(getRequestedFor(urlPathEqualTo("/v1/refunds"))
                .withQueryParam("reference", equalTo(REFUND_ID.toString())));
    }

    @Test
    void shouldReportRefundAcquirerDoesNotHave() {
        acquirer.stubFor(get(urlPathEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(404)));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.notFound());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void shouldNotKnowWhenRefundLookupFails(int status) {
        acquirer.stubFor(get(urlPathEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(status)));

        assertThat(connector.lookupRefund(LOOKUP)).isEqualTo(ProviderRefundResult.unknown());
    }

    private static AcquirerSimConnector connectorFor(String baseUrl) {
        return new AcquirerSimConnector(RestClient.builder(),
                new AcquirerSimProperties(baseUrl, API_KEY, Duration.ofMillis(300), Duration.ofMillis(700)));
    }

    // A refund as acquirer-sim answers it, with fields the gateway doesn't read.
    private static ResponseDefinitionBuilder refund(int status, String refundStatus, @Nullable String failureCode) {
        String failureCodeJson = failureCode == null ? "null" : "\"" + failureCode + "\"";
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id": "re_test_1", "charge_id": "ch_test_1", "reference": "%s", "amount": 5000,
                         "currency": "UAH", "status": "%s", "failure_code": %s,
                         "created_at": "2026-11-25T10:01:00Z"}"""
                        .formatted(REFUND_ID, refundStatus, failureCodeJson));
    }
}
