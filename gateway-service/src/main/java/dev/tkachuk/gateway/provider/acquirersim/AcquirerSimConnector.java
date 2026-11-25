package dev.tkachuk.gateway.provider.acquirersim;

import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderConnector;
import dev.tkachuk.gateway.provider.ProviderResult;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * acquirer-sim, the simulated card acquirer in this repository, over its REST API. What matters most is telling
 * "not charged" from "don't know": a 4xx or a connection that never opened is FAILED, while a 5xx, a read timeout or
 * an unreadable answer is UNKNOWN, and the payment waits for a lookup.
 */
@Component
class AcquirerSimConnector implements ProviderConnector {

    static final String NAME = "acquirer-sim";

    private static final Logger log = LoggerFactory.getLogger(AcquirerSimConnector.class);

    private final RestClient restClient;

    AcquirerSimConnector(RestClient.Builder restClientBuilder, AcquirerSimProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderResult charge(ChargeRequest request) {
        UUID reference = request.reference();
        try {
            SimCharge charge = restClient.post()
                    .uri("/v1/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SimChargeRequest.from(request))
                    .retrieve()
                    .body(SimCharge.class);
            return resultOf(charge, reference);
        } catch (HttpClientErrorException e) {
            log.warn("acquirer-sim refused the charge for payment {}: HTTP {}", reference, e.getStatusCode().value());
            return ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST);
        } catch (HttpServerErrorException e) {
            log.warn("acquirer-sim failed on the charge for payment {}: HTTP {}; the card may have been charged",
                    reference, e.getStatusCode().value());
            return ProviderResult.unknown();
        } catch (ResourceAccessException e) {
            if (neverConnected(e)) {
                log.warn("acquirer-sim is unreachable, so payment {} was not sent: {}", reference, describe(e));
                return ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
            }
            log.warn("No answer from acquirer-sim for payment {}: {}", reference, describe(e));
            return ProviderResult.unknown();
        } catch (RestClientException e) {
            log.warn("acquirer-sim's answer for payment {} could not be read: {}", reference, describe(e));
            return ProviderResult.unknown();
        }
    }

    @Override
    public ProviderResult lookup(UUID reference) {
        try {
            SimCharge charge = restClient.get()
                    .uri("/v1/charges?reference={reference}", reference)
                    .retrieve()
                    .body(SimCharge.class);
            return resultOf(charge, reference);
        } catch (HttpClientErrorException.NotFound e) {
            return ProviderResult.notFound();
        } catch (RestClientException e) {
            log.warn("Could not ask acquirer-sim about payment {}: {}", reference, describe(e));
            return ProviderResult.unknown();
        }
    }

    private static ProviderResult resultOf(@Nullable SimCharge charge, UUID reference) {
        if (charge == null || charge.id() == null || charge.status() == null) {
            log.warn("acquirer-sim answered for payment {} without a charge id or status", reference);
            return ProviderResult.unknown();
        }
        return switch (charge.status()) {
            case "approved" -> ProviderResult.approved(charge.id());
            case "declined" -> ProviderResult.declined(charge.id(), charge.declineCode());
            default -> {
                log.warn("acquirer-sim answered for payment {} with an unknown status", reference);
                yield ProviderResult.unknown();
            }
        };
    }

    // Connection refused, an unknown host or a connect timeout: the request never left this machine. Anything
    // later, such as a read timeout or a dropped connection, can happen after the acquirer got the request.
    private static boolean neverConnected(ResourceAccessException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    // An HTTP error by its status only, since its message quotes the response body; an I/O error by its type, since
    // some have no message at all (a refused connection).
    private static String describe(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        Throwable cause = e.getCause();
        if (e instanceof ResourceAccessException && cause != null) {
            String type = cause.getClass().getSimpleName();
            return cause.getMessage() == null ? type : type + ": " + cause.getMessage();
        }
        return e.getMessage();
    }
}
