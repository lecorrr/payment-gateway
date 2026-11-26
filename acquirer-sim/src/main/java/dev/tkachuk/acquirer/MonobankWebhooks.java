package dev.tkachuk.acquirer;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * monobank-style webhooks: the invoice, signed in X-Sign, POSTed to the webHookUrl its payment-direct gave, when the
 * charge is decided and when one of its refunds is finished. Like monobank, acquirer-sim tries up to three times,
 * until the receiver answers 2xx. Sending happens in the background, so neither the API nor the challenge page waits
 * for the receiver.
 */
@Component
class MonobankWebhooks {

    private static final int ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(MonobankWebhooks.class);

    private final MonobankKeys keys;
    private final JsonMapper jsonMapper;
    private final TaskExecutor executor;
    private final ChargeService charges;
    private final RefundService refunds;
    private final RestClient restClient;
    // The webhook URL of each charge that has one; past sim.max-charges the oldest is forgotten, as the charges are.
    private final Map<String, String> urlByChargeId;

    MonobankWebhooks(MonobankKeys keys, JsonMapper jsonMapper, TaskExecutor executor, ChargeService charges,
            RefundService refunds, RestClient.Builder restClientBuilder, SimProperties properties) {
        this.keys = keys;
        this.jsonMapper = jsonMapper;
        this.executor = executor;
        this.charges = charges;
        this.refunds = refunds;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        // Spring's builder, so the request carries the trace on to the receiver.
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        int maxCharges = properties.maxCharges();
        this.urlByChargeId = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxCharges;
            }
        };
    }

    synchronized void register(String chargeId, String webhookUrl) {
        urlByChargeId.put(chargeId, webhookUrl);
    }

    /** Sends the charge's invoice to the charge's webhook URL; a charge without one sends nothing. */
    void send(Charge charge) {
        String url;
        synchronized (this) {
            url = urlByChargeId.get(charge.id());
        }
        if (url == null) {
            return;
        }
        // The exact bytes that are signed are the ones sent.
        byte[] body = jsonMapper.writeValueAsBytes(MonobankInvoice.of(charge, refunds.ofCharge(charge.id())));
        String signature = keys.sign(body);
        executor.execute(() -> deliver(URI.create(url), body, signature, charge.id()));
    }

    @EventListener
    public void onChargeChanged(ChargeChanged event) {
        send(event.charge());
    }

    @EventListener
    public void onRefundChanged(RefundChanged event) {
        charges.findById(event.refund().chargeId()).ifPresent(stored -> send(stored.charge()));
    }

    private void deliver(URI url, byte[] body, String signature, String invoiceId) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Sign", signature)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Webhook for invoice {} delivered, attempt {}", invoiceId, attempt);
                return;
            } catch (RestClientException e) {
                log.warn("Webhook for invoice {} not delivered, attempt {} of {}: {}", invoiceId, attempt, ATTEMPTS,
                        e.getMessage());
            }
            if (attempt < ATTEMPTS && !pause()) {
                return;
            }
        }
    }

    // False when the application is stopping.
    private static boolean pause() {
        try {
            Thread.sleep(RETRY_DELAY);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
