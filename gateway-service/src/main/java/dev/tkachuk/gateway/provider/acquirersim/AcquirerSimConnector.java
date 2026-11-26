package dev.tkachuk.gateway.provider.acquirersim;

import static dev.tkachuk.gateway.provider.ProviderHttp.describe;
import static dev.tkachuk.gateway.provider.ProviderHttp.isHttpUrl;
import static dev.tkachuk.gateway.provider.ProviderHttp.neverConnected;

import dev.tkachuk.gateway.provider.ChargeLookup;
import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderConnector;
import dev.tkachuk.gateway.provider.ProviderHttp;
import dev.tkachuk.gateway.provider.ProviderRefundResult;
import dev.tkachuk.gateway.provider.ProviderResult;
import dev.tkachuk.gateway.provider.RefundLookup;
import dev.tkachuk.gateway.provider.RefundRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * acquirer-sim, the simulated card acquirer in this repository, over its REST API. What matters most is telling "not
 * charged" from "don't know": a 4xx or a connection that never opened is FAILED, while a 5xx, a read timeout or an
 * unreadable answer is UNKNOWN, and the payment waits for a lookup. A connection that never opened and a 429 are the
 * provider being unavailable, which lets another provider take the charge. "requires_action" is a 3-D Secure
 * challenge, passed on with the challenge's URL. Charges are looked up by the payment's id, which acquirer-sim keeps
 * as their reference, and refunds by the refund's id in the same way.
 */
@Component
class AcquirerSimConnector implements ProviderConnector {

    static final String NAME = "acquirer-sim";

    private static final Logger log = LoggerFactory.getLogger(AcquirerSimConnector.class);

    private final RestClient restClient;

    AcquirerSimConnector(RestClient.Builder restClientBuilder, AcquirerSimProperties properties) {
        this.restClient = ProviderHttp.withTimeouts(restClientBuilder, properties.connectTimeout(),
                        properties.readTimeout())
                .baseUrl(properties.baseUrl())
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
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("acquirer-sim turned the charge for payment {} away: HTTP 429, too many requests", reference);
            return ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
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
    public ProviderResult lookup(ChargeLookup lookup) {
        UUID reference = lookup.reference();
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

    /**
     * Refunds with the refund's id as acquirer-sim's reference, so a repeat after a lost answer gets the same refund
     * back rather than a second one. The same rules as for a charge tell "not refunded" from "don't know".
     */
    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        UUID reference = request.reference();
        try {
            SimRefund refund = restClient.post()
                    .uri("/v1/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SimRefundRequest.from(request))
                    .retrieve()
                    .body(SimRefund.class);
            return refundResultOf(refund, reference);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("acquirer-sim turned refund {} away: HTTP 429, too many requests", reference);
            return ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            log.warn("acquirer-sim refused refund {}: HTTP {}", reference, e.getStatusCode().value());
            return ProviderRefundResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST);
        } catch (HttpServerErrorException e) {
            log.warn("acquirer-sim failed on refund {}: HTTP {}; the money may be on its way back", reference,
                    e.getStatusCode().value());
            return ProviderRefundResult.unknown();
        } catch (ResourceAccessException e) {
            if (neverConnected(e)) {
                log.warn("acquirer-sim is unreachable, so refund {} was not sent: {}", reference, describe(e));
                return ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
            }
            log.warn("No answer from acquirer-sim for refund {}: {}", reference, describe(e));
            return ProviderRefundResult.unknown();
        } catch (RestClientException e) {
            log.warn("acquirer-sim's answer for refund {} could not be read: {}", reference, describe(e));
            return ProviderRefundResult.unknown();
        }
    }

    @Override
    public ProviderRefundResult lookupRefund(RefundLookup lookup) {
        UUID reference = lookup.reference();
        try {
            SimRefund refund = restClient.get()
                    .uri("/v1/refunds?reference={reference}", reference)
                    .retrieve()
                    .body(SimRefund.class);
            return refundResultOf(refund, reference);
        } catch (HttpClientErrorException.NotFound e) {
            return ProviderRefundResult.notFound();
        } catch (RestClientException e) {
            log.warn("Could not ask acquirer-sim about refund {}: {}", reference, describe(e));
            return ProviderRefundResult.unknown();
        }
    }

    private static ProviderRefundResult refundResultOf(@Nullable SimRefund refund, UUID reference) {
        if (refund == null || refund.id() == null || refund.status() == null) {
            log.warn("acquirer-sim answered for refund {} without a refund id or status", reference);
            return ProviderRefundResult.unknown();
        }
        return switch (refund.status()) {
            case "succeeded" -> ProviderRefundResult.succeeded(refund.id());
            case "pending" -> ProviderRefundResult.pending(refund.id());
            case "failed" -> ProviderRefundResult.failed(refund.id(),
                    refund.failureCode() == null ? FailureCodes.REFUND_DECLINED : refund.failureCode());
            default -> {
                log.warn("acquirer-sim answered for refund {} with an unknown status", reference);
                yield ProviderRefundResult.unknown();
            }
        };
    }

    private static ProviderResult resultOf(@Nullable SimCharge charge, UUID reference) {
        if (charge == null || charge.id() == null || charge.status() == null) {
            log.warn("acquirer-sim answered for payment {} without a charge id or status", reference);
            return ProviderResult.unknown();
        }
        return switch (charge.status()) {
            case "approved" -> ProviderResult.approved(charge.id());
            case "declined" -> ProviderResult.declined(charge.id(), charge.declineCode());
            case "requires_action" -> requiresAction(charge.id(), charge.actionUrl(), reference);
            default -> {
                log.warn("acquirer-sim answered for payment {} with an unknown status", reference);
                yield ProviderResult.unknown();
            }
        };
    }

    // Without a URL the customer can't be sent to the challenge; the payment waits, and a lookup asks again.
    private static ProviderResult requiresAction(String chargeId, @Nullable String actionUrl, UUID reference) {
        if (actionUrl == null || !isHttpUrl(actionUrl)) {
            log.warn("acquirer-sim asked for a 3-D Secure challenge for payment {} without a usable action_url",
                    reference);
            return ProviderResult.unknown();
        }
        return ProviderResult.requiresAction(chargeId, actionUrl);
    }
}
