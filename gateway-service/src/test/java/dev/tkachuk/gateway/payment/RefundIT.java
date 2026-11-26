package dev.tkachuk.gateway.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.merchant.ApiKeys;
import dev.tkachuk.gateway.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Refunds of payments acquirer-sim charged: in full and in parts, never above the amount even when sent at once,
 * once per Idempotency-Key, and looked up in the background when their answer was lost.
 */
class RefundIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private MeterRegistry meters;

    @Test
    void shouldRefundWholePaymentAndShowItOnPayment() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null));

        String location = createRefund(paymentId, "refund-1", 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.provider").value("acquirer-sim"))
                .andExpect(jsonPath("$.providerReference").value("re_test_1"))
                .andExpect(jsonPath("$.failureCode").doesNotExist())
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("/api/v1/payments/" + paymentId + "/refunds/");
        String refundId = location.substring(location.lastIndexOf('/') + 1);
        acquirer.verify(postRequestedFor(urlEqualTo("/v1/refunds")).withRequestBody(equalToJson("""
                {"charge_id": "ch_test_1", "reference": "%s", "amount": 15000}""".formatted(refundId))));
        getRefund(paymentId, refundId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        getPayment(DEMO_API_KEY, paymentId)
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refundedAmount").value(15000));
    }

    @Test
    void shouldRefundInPartsUntilNothingIsLeft() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null));

        createRefund(paymentId, "refund-1", 5000).andExpect(status().isCreated());
        createRefund(paymentId, "refund-2", 10000).andExpect(status().isCreated());
        createRefund(paymentId, "refund-3", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value("Nothing of this payment is left to refund"));

        listRefunds(paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(10000))
                .andExpect(jsonPath("$[1].amount").value(5000));
        getPayment(DEMO_API_KEY, paymentId).andExpect(jsonPath("$.refundedAmount").value(15000));
        acquirer.verify(2, postRequestedFor(urlEqualTo("/v1/refunds")));
    }

    @Test
    void shouldRefuseMoreThanIsLeftWithoutAskingProvider() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        createRefund(paymentId, "refund-1", 5000).andExpect(status().isCreated());

        createRefund(paymentId, "refund-2", 10001)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value(containsString("Only 10000 of this payment's 15000")));

        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/refunds")));
    }

    @Test
    void shouldReplaySameRequestWithoutRefundingAgain() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        String id = refundId(createRefund(paymentId, "refund-1", 5000)
                .andExpect(header().doesNotExist("Idempotent-Replayed")));

        createRefund(paymentId, "refund-1", 5000)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/refunds")));
        getPayment(DEMO_API_KEY, paymentId).andExpect(jsonPath("$.refundedAmount").value(5000));
    }

    @Test
    void shouldRefuseSameKeyWithDifferentRequest() throws Exception {
        String paymentId = succeededPayment("pay-1");
        String otherPaymentId = succeededPayment("pay-2");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        createRefund(paymentId, "refund-1", 5000).andExpect(status().isCreated());

        createRefund(paymentId, "refund-1", 6000)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        createRefund(otherPaymentId, "refund-1", 5000)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        acquirer.verify(1, postRequestedFor(urlEqualTo("/v1/refunds")));
    }

    @Test
    void shouldRefuseRefundOfPaymentThatDidNotSucceed() throws Exception {
        acquirerAnswersCharge(charge(201, "declined", "insufficient_funds"));
        String paymentId = JsonPath.read(createPayment("pay-1", paymentRequest(VISA))
                .andReturn().getResponse().getContentAsString(), "$.id");

        createRefund(paymentId, "refund-1", 5000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"))
                .andExpect(jsonPath("$.detail").value(containsString("this one is DECLINED")));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/refunds")));
    }

    @Test
    void shouldKeepPaymentsOfOtherMerchantsOutOfReach() throws Exception {
        String otherKey = "test-other-merchant-key-0123456789";
        jdbc.update("""
                INSERT INTO merchants (id, code, name, api_key_hash, created_at)
                VALUES (?, 'other-shop', 'Other Shop', ?, now())""", UUID.randomUUID(), ApiKeys.hash(otherKey));
        String paymentId = succeededPayment("pay-1");

        createRefund(otherKey, paymentId, "refund-1", 5000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        listRefunds(UUID.randomUUID().toString()).andExpect(status().isNotFound());
        getRefund(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));

        acquirer.verify(0, postRequestedFor(urlEqualTo("/v1/refunds")));
    }

    // 429: acquirer-sim never took the refund, so its amount can be refunded again, under a new key.
    @Test
    void shouldFailRefundProviderTurnedAwayAndFreeItsAmount() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(aResponse().withStatus(429));

        createRefund(paymentId, "refund-1", 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("provider_unavailable"));

        acquirerAnswersRefund(refund(201, "succeeded", null));
        createRefund(paymentId, "refund-2", 15000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void shouldPassOnProvidersRefusal() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "failed", "refund_declined"));

        createRefund(paymentId, "refund-1", 5000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("refund_declined"));

        getPayment(DEMO_API_KEY, paymentId).andExpect(jsonPath("$.refundedAmount").value(0));
    }

    // The answer comes after the read timeout: the refund waits, and the checker finds it by its reference.
    @Test
    void shouldFindRefundWhoseAnswerWasLost() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null).withFixedDelay(1500));

        String refundId = refundId(createRefund(paymentId, "refund-1", 5000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING")));
        acquirerAnswersRefundLookup(refundId, refund(200, "succeeded", null));

        await().atMost(WAIT).untilAsserted(() -> getRefund(paymentId, refundId)
                .andExpect(jsonPath("$.status").value("SUCCEEDED")));
        getPayment(DEMO_API_KEY, paymentId).andExpect(jsonPath("$.refundedAmount").value(5000));
    }

    // A 500: acquirer-sim may or may not have it. It never shows up, so the refund fails after give-up-after.
    @Test
    void shouldGiveUpRefundProviderHasNoRecordOf() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(aResponse().withStatus(500));

        String refundId = refundId(createRefund(paymentId, "refund-1", 5000)
                .andExpect(jsonPath("$.status").value("PROCESSING")));
        acquirerAnswersRefundLookup(refundId, aResponse().withStatus(404));

        await().atMost(WAIT).untilAsserted(() -> getRefund(paymentId, refundId)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("provider_no_record")));
        getPayment(DEMO_API_KEY, paymentId).andExpect(jsonPath("$.refundedAmount").value(0));
    }

    // Eight refunds of 3000 from a payment of 15000 at the same moment: exactly five fit, whatever the order.
    @Test
    void shouldNeverRefundMoreThanAmountWhenRefundsComeAtOnce() throws Exception {
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        int refunds = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int n = 1; n <= refunds; n++) {
            String key = "refund-" + n;
            requests.add(() -> {
                start.await();
                return createRefund(paymentId, key, 3000).andReturn().getResponse().getStatus();
            });
        }

        List<Integer> statuses = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(refunds);
        try {
            List<Future<Integer>> answers = requests.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(5);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM refunds WHERE status = 'SUCCEEDED'", Long.class))
                .isEqualTo(15000);
        getPayment(DEMO_API_KEY, paymentId).andExpect(jsonPath("$.refundedAmount").value(15000));
    }

    @Test
    void shouldSendWebhookWithRefundsOutcome() throws Exception {
        jdbc.update("""
                INSERT INTO webhook_endpoints (merchant_id, url, secret, created_at, updated_at)
                SELECT id, ?, 'whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw', now(), now()
                FROM merchants WHERE code = 'demo-shop'""", merchantEndpoint.baseUrl() + "/webhooks");
        merchantEndpoint.stubFor(post("/webhooks").willReturn(aResponse().withStatus(204)));
        String paymentId = succeededPayment("pay-1");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        String refundId = refundId(createRefund(paymentId, "refund-1", 5000));

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT type FROM webhook_events WHERE status = 'DELIVERED' ORDER BY created_at", String.class))
                .containsExactly("payment.succeeded", "refund.succeeded"));

        LoggedRequest refundEvent = merchantEndpoint.findAll(postRequestedFor(urlEqualTo("/webhooks"))).stream()
                .filter(request -> request.getBodyAsString().contains("refund.succeeded"))
                .findFirst().orElseThrow();
        String body = refundEvent.getBodyAsString();
        assertThat(JsonPath.<String>read(body, "$.data.id")).isEqualTo(refundId);
        assertThat(JsonPath.<String>read(body, "$.data.paymentId")).isEqualTo(paymentId);
        assertThat(JsonPath.<String>read(body, "$.data.status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldCountRefundsByOutcome() throws Exception {
        String paymentId = succeededPayment("pay-1");
        double before = refundsCounted("SUCCEEDED", "none");
        double declinedBefore = refundsCounted("FAILED", "refund_declined");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        createRefund(paymentId, "refund-1", 5000).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        acquirerAnswersRefund(refund(201, "failed", "refund_declined"));
        createRefund(paymentId, "refund-2", 5000).andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(refundsCounted("SUCCEEDED", "none") - before).isEqualTo(1);
        assertThat(refundsCounted("FAILED", "refund_declined") - declinedBefore).isEqualTo(1);
    }

    private double refundsCounted(String status, String code) {
        var counter = meters.find(PaymentMetrics.REFUNDS)
                .tags("status", status, "provider", "acquirer-sim", "code", code)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static String refundId(ResultActions answer) throws Exception {
        return JsonPath.read(answer.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
