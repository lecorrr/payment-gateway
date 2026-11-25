package dev.tkachuk.gateway.webhook;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends one event to one endpoint, signed. Any 2xx is delivered. Redirects are not followed and count as a failure,
 * as Standard Webhooks says: an endpoint that moved should be updated instead. 410 Gone means gone for good.
 */
@Component
class WebhookSender {

    static final String ID_HEADER = "webhook-id";
    static final String TIMESTAMP_HEADER = "webhook-timestamp";
    static final String SIGNATURE_HEADER = "webhook-signature";

    private final RestClient restClient;

    WebhookSender(RestClient.Builder restClientBuilder, WebhookProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    /** now: when this attempt is made, which goes into the signature as the webhook-timestamp. */
    DeliveryResult send(WebhookEndpoint endpoint, WebhookEvent event, Instant now) {
        byte[] body = event.payload().getBytes(StandardCharsets.UTF_8);
        String messageId = event.id().toString();
        long timestamp = now.getEpochSecond();
        try {
            int status = restClient.post()
                    .uri(endpoint.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(ID_HEADER, messageId)
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(SIGNATURE_HEADER, WebhookSignatures.sign(endpoint.secret(), messageId, timestamp, body))
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status >= 200 && status < 300) {
                return DeliveryResult.delivered();
            }
            return status == 410 ? DeliveryResult.gone() : DeliveryResult.failed("HTTP " + status);
        } catch (RestClientException e) {
            return DeliveryResult.failed(describe(e));
        }
    }

    // An I/O error by its type, since some have no message (a refused connection).
    private static String describe(RestClientException e) {
        Throwable cause = e.getCause();
        if (e instanceof ResourceAccessException && cause != null) {
            String type = cause.getClass().getSimpleName();
            return cause.getMessage() == null ? type : type + ": " + cause.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
