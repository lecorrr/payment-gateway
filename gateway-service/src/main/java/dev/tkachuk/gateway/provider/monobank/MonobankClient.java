package dev.tkachuk.gateway.provider.monobank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.tkachuk.gateway.provider.ProviderHttp;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The calls to monobank's acquiring API the gateway makes, with the merchant's token in X-Token. Each returns what
 * monobank answered, or throws Spring's RestClientException; telling what that means is the caller's job.
 */
@Component
class MonobankClient {

    private final RestClient restClient;

    MonobankClient(RestClient.Builder restClientBuilder, MonobankProperties properties) {
        this.restClient = ProviderHttp.withTimeouts(restClientBuilder, properties.connectTimeout(),
                        properties.readTimeout())
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Token", properties.token())
                .build();
    }

    /** Creates an invoice and pays it with the card's details. */
    @Nullable
    Invoice payDirect(PaymentDirectRequest request) {
        return restClient.post()
                .uri("/api/merchant/invoice/payment-direct")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Invoice.class);
    }

    /** The invoice as it is now; 404 for an invoice monobank doesn't have. */
    @Nullable
    Invoice invoice(String invoiceId) {
        return restClient.get()
                .uri("/api/merchant/invoice/status?invoiceId={invoiceId}", invoiceId)
                .retrieve()
                .body(Invoice.class);
    }

    /** The payments since this moment, newest first. */
    @Nullable
    Statement statement(Instant from) {
        return restClient.get()
                .uri("/api/merchant/statement?from={from}", from.getEpochSecond())
                .retrieve()
                .body(Statement.class);
    }

    /** The key monobank signs webhooks with: Base64 of a PEM block. */
    @Nullable
    String publicKey() {
        PublicKeyAnswer answer = restClient.get()
                .uri("/api/merchant/pubkey")
                .retrieve()
                .body(PublicKeyAnswer.class);
        return answer == null ? null : answer.key();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublicKeyAnswer(@Nullable String key) {
    }
}
