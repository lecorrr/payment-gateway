package dev.tkachuk.gateway.provider.monobank;

import static dev.tkachuk.gateway.provider.ProviderHttp.describe;
import static dev.tkachuk.gateway.provider.ProviderHttp.isHttpUrl;
import static dev.tkachuk.gateway.provider.ProviderHttp.neverConnected;

import dev.tkachuk.gateway.provider.ChargeLookup;
import dev.tkachuk.gateway.provider.ChargeRequest;
import dev.tkachuk.gateway.provider.FailureCodes;
import dev.tkachuk.gateway.provider.ProviderConnector;
import dev.tkachuk.gateway.provider.ProviderRefundResult;
import dev.tkachuk.gateway.provider.ProviderResult;
import dev.tkachuk.gateway.provider.RefundLookup;
import dev.tkachuk.gateway.provider.RefundRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * monobank's acquiring API, charging with the card's details (payment-direct, which monobank opens to merchants
 * with a PCI DSS certificate). The payment's id goes as the invoice's reference, and the gateway's return page as
 * redirectUrl, where monobank sends the customer after 3-D Secure (tdsUrl). An invoice still "processing" is
 * PENDING; failures come with monobank's errCode, turned into the gateway's decline codes. As with every connector,
 * "not charged" is told from "don't know": a 4xx or a connection that never opened is FAILED, while a 5xx, a read
 * timeout or an unreadable answer is UNKNOWN. When the answer to a charge was lost, the gateway has no invoice id,
 * so a lookup first finds the invoice by its reference in monobank's statement. Refunds go through invoice/cancel.
 */
@Component
class MonobankConnector implements ProviderConnector {

    static final String NAME = "monobank";

    // Room for the gateway's clock and monobank's to differ when the statement is searched from the charge's time.
    private static final Duration STATEMENT_MARGIN = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(MonobankConnector.class);

    private final MonobankClient client;
    private final MonobankProperties properties;

    MonobankConnector(MonobankClient client, MonobankProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> missingSettings() {
        return properties.isConfigured()
                ? List.of()
                : List.of("gateway.providers.monobank.token (GATEWAY_MONOBANK_TOKEN)");
    }

    @Override
    public ProviderResult charge(ChargeRequest request) {
        UUID reference = request.reference();
        String webhookUrl = properties.webhookUrl().isEmpty() ? null : properties.webhookUrl();
        try {
            Invoice invoice = client.payDirect(PaymentDirectRequest.from(request, webhookUrl));
            return resultOf(withErrCode(invoice), reference);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("monobank turned the charge for payment {} away: HTTP 429, too many requests", reference);
            return ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            log.warn("monobank refused the charge for payment {}: HTTP {}", reference, e.getStatusCode().value());
            return ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST);
        } catch (HttpServerErrorException e) {
            log.warn("monobank failed on the charge for payment {}: HTTP {}; the card may have been charged",
                    reference, e.getStatusCode().value());
            return ProviderResult.unknown();
        } catch (ResourceAccessException e) {
            if (neverConnected(e)) {
                log.warn("monobank is unreachable, so payment {} was not sent: {}", reference, describe(e));
                return ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
            }
            log.warn("No answer from monobank for payment {}: {}", reference, describe(e));
            return ProviderResult.unknown();
        } catch (RestClientException e) {
            log.warn("monobank's answer for payment {} could not be read: {}", reference, describe(e));
            return ProviderResult.unknown();
        }
    }

    @Override
    public ProviderResult lookup(ChargeLookup lookup) {
        UUID reference = lookup.reference();
        if (lookup.providerReference() != null) {
            return invoiceResult(lookup.providerReference(), reference);
        }
        Statement statement;
        try {
            statement = client.statement(lookup.chargedAt().minus(STATEMENT_MARGIN));
        } catch (RestClientException e) {
            log.warn("Could not search monobank's statement for payment {}: {}", reference, describe(e));
            return ProviderResult.unknown();
        }
        if (statement == null || statement.list() == null) {
            log.warn("monobank's statement came without a list, so payment {} could not be looked for", reference);
            return ProviderResult.unknown();
        }
        return statement.invoiceIdOf(reference)
                .map(invoiceId -> invoiceResult(invoiceId, reference))
                .orElseGet(ProviderResult::notFound);
    }

    /**
     * Refunds through invoice/cancel, with the refund's id as extRef. monobank usually answers "processing" and
     * finishes the refund later, telling by webhook; the invoice's cancelList shows each refund by its extRef.
     */
    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        UUID reference = request.reference();
        try {
            MonobankClient.CancelAnswer answer = client.cancel(CancelRequest.from(request));
            return refundResultOf(answer == null ? null : answer.status(), reference);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("monobank turned refund {} away: HTTP 429, too many requests", reference);
            return ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            log.warn("monobank refused refund {}: HTTP {}", reference, e.getStatusCode().value());
            return ProviderRefundResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST);
        } catch (HttpServerErrorException e) {
            log.warn("monobank failed on refund {}: HTTP {}; the money may be on its way back", reference,
                    e.getStatusCode().value());
            return ProviderRefundResult.unknown();
        } catch (ResourceAccessException e) {
            if (neverConnected(e)) {
                log.warn("monobank is unreachable, so refund {} was not sent: {}", reference, describe(e));
                return ProviderRefundResult.failed(FailureCodes.PROVIDER_UNAVAILABLE);
            }
            log.warn("No answer from monobank for refund {}: {}", reference, describe(e));
            return ProviderRefundResult.unknown();
        } catch (RestClientException e) {
            log.warn("monobank's answer for refund {} could not be read: {}", reference, describe(e));
            return ProviderRefundResult.unknown();
        }
    }

    /** A refund is found in its invoice's cancelList; an invoice that lists no such extRef never got it. */
    @Override
    public ProviderRefundResult lookupRefund(RefundLookup lookup) {
        UUID reference = lookup.reference();
        Invoice invoice;
        try {
            invoice = client.invoice(lookup.chargeReference());
        } catch (HttpClientErrorException.NotFound e) {
            return ProviderRefundResult.notFound();
        } catch (RestClientException e) {
            log.warn("Could not ask monobank about refund {}: {}", reference, describe(e));
            return ProviderRefundResult.unknown();
        }
        if (invoice == null || invoice.invoiceId() == null) {
            log.warn("monobank answered for refund {} without an invoice", reference);
            return ProviderRefundResult.unknown();
        }
        return invoice.cancelOf(CancelRequest.extRefOf(reference))
                .map(cancel -> refundResultOf(cancel.status(), reference))
                .orElseGet(ProviderRefundResult::notFound);
    }

    private static ProviderRefundResult refundResultOf(@Nullable String status, UUID reference) {
        if (status == null) {
            log.warn("monobank answered for refund {} without a status", reference);
            return ProviderRefundResult.unknown();
        }
        return switch (status) {
            case "success" -> ProviderRefundResult.succeeded(null);
            case "processing" -> ProviderRefundResult.pending(null);
            case "failure" -> ProviderRefundResult.failed(FailureCodes.REFUND_DECLINED);
            default -> {
                log.warn("monobank answered for refund {} with status {}, which the gateway does not know",
                        reference, status);
                yield ProviderRefundResult.unknown();
            }
        };
    }

    // payment-direct gives the reason for a failure in words only (failureReason); the invoice's status has the code.
    @Nullable
    private Invoice withErrCode(@Nullable Invoice invoice) {
        if (invoice == null || invoice.invoiceId() == null || !"failure".equals(invoice.status())
                || invoice.errCode() != null) {
            return invoice;
        }
        try {
            Invoice status = client.invoice(invoice.invoiceId());
            return status == null ? invoice : invoice.withErrCode(status.errCode());
        } catch (RestClientException e) {
            log.warn("Could not ask monobank why invoice {} failed: {}", invoice.invoiceId(), describe(e));
            return invoice;
        }
    }

    // Only the invoice status answers 404, and then for an invoice monobank does not have.
    private ProviderResult invoiceResult(String invoiceId, UUID reference) {
        try {
            return resultOf(client.invoice(invoiceId), reference);
        } catch (HttpClientErrorException.NotFound e) {
            return ProviderResult.notFound();
        } catch (RestClientException e) {
            log.warn("Could not ask monobank about payment {}: {}", reference, describe(e));
            return ProviderResult.unknown();
        }
    }

    private static ProviderResult resultOf(@Nullable Invoice invoice, UUID reference) {
        if (invoice == null || invoice.invoiceId() == null || invoice.status() == null) {
            log.warn("monobank answered for payment {} without an invoice id or status", reference);
            return ProviderResult.unknown();
        }
        String invoiceId = invoice.invoiceId();
        return switch (invoice.status()) {
            case "success" -> ProviderResult.approved(invoiceId);
            case "failure" -> declined(invoice, invoiceId, reference);
            case "expired" -> ProviderResult.declined(invoiceId, "authentication_expired");
            case "created", "processing" -> challengeOrPending(invoice, invoiceId, reference);
            default -> {
                // hold can't follow a debit the gateway made, and reversed only follows a refund of a payment that
                // succeeded already, which is never looked up: someone has to look at the invoice.
                log.warn("monobank answered for payment {} with status {}, which the gateway does not act on",
                        reference, invoice.status());
                yield ProviderResult.pending(invoiceId);
            }
        };
    }

    private static ProviderResult declined(Invoice invoice, String invoiceId, UUID reference) {
        String declineCode = MonobankDeclineCodes.of(invoice.errCode());
        log.info("monobank declined payment {} with errCode {} ({}): {}", reference, invoice.errCode(),
                invoice.failureReason(), declineCode);
        return ProviderResult.declined(invoiceId, declineCode);
    }

    // A tdsUrl means the card's bank wants 3-D Secure; without one, monobank is still working on the charge.
    private static ProviderResult challengeOrPending(Invoice invoice, String invoiceId, UUID reference) {
        String tdsUrl = invoice.tdsUrl();
        if (tdsUrl == null) {
            return ProviderResult.pending(invoiceId);
        }
        if (!isHttpUrl(tdsUrl)) {
            log.warn("monobank asked for 3-D Secure for payment {} with a tdsUrl no browser can be sent to",
                    reference);
            return ProviderResult.pending(invoiceId);
        }
        return ProviderResult.requiresAction(invoiceId, tdsUrl);
    }
}
