package dev.tkachuk.gateway.provider.monobank;

import dev.tkachuk.gateway.provider.ChargeNotifications;
import dev.tkachuk.gateway.provider.ProviderHttp;
import dev.tkachuk.gateway.provider.RefundNotifications;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where monobank reports invoice changes, once gateway.providers.monobank.webhook-url points here: a payment's
 * outcome, and its refunds in cancelList. Only a body monobank signed counts, and even that is only a reason to ask:
 * the payment, and each of its refunds still PROCESSING, is looked up with monobank again.
 * monobank sends a webhook again, up to three times, until it gets 200, so a signed report the gateway can't use
 * gets 200 too; there is nothing a resend would change.
 */
@RestController
class MonobankWebhookController {

    static final String PATH = "/providers/monobank/webhooks";

    private static final Logger log = LoggerFactory.getLogger(MonobankWebhookController.class);

    private final MonobankProperties properties;
    private final MonobankPublicKey publicKey;
    private final ChargeNotifications charges;
    private final RefundNotifications refunds;
    private final JsonMapper jsonMapper;

    MonobankWebhookController(MonobankProperties properties, MonobankPublicKey publicKey,
            ChargeNotifications charges, RefundNotifications refunds, JsonMapper jsonMapper) {
        this.properties = properties;
        this.publicKey = publicKey;
        this.charges = charges;
        this.refunds = refunds;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(PATH)
    ResponseEntity<Void> receive(@RequestHeader(name = "X-Sign", required = false) @Nullable String signature,
            @RequestBody byte[] body) {
        if (!properties.isConfigured()) {
            return ResponseEntity.notFound().build();
        }
        if (signature == null || signature.isBlank()) {
            log.warn("A monobank webhook was refused: it has no X-Sign header");
            return ResponseEntity.badRequest().build();
        }
        boolean signed;
        try {
            signed = publicKey.signed(body, signature);
        } catch (RestClientException e) {
            log.error("A monobank webhook can't be checked, its key can't be fetched: {}", ProviderHttp.describe(e));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (IllegalStateException e) {
            log.error("A monobank webhook can't be checked: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!signed) {
            log.warn("A monobank webhook was refused: its X-Sign does not match the body");
            return ResponseEntity.badRequest().build();
        }

        Invoice invoice;
        try {
            invoice = jsonMapper.readValue(body, Invoice.class);
        } catch (JacksonException e) {
            invoice = null;
        }
        if (invoice == null) {
            log.warn("A signed monobank webhook has a body that is no invoice");
            return ResponseEntity.ok().build();
        }
        UUID paymentId = paymentIdOf(invoice);
        if (paymentId == null) {
            log.warn("monobank reports invoice {}, whose reference is no payment of this gateway", invoice.invoiceId());
            return ResponseEntity.ok().build();
        }
        log.info("monobank reports invoice {} of payment {} is {}", invoice.invoiceId(), paymentId, invoice.status());
        // The same webhook reports the payment and its refunds; each side asks only if it is still waiting.
        charges.chargeChanged(MonobankConnector.NAME, paymentId);
        refunds.refundsChanged(MonobankConnector.NAME, paymentId);
        return ResponseEntity.ok().build();
    }

    @Nullable
    private static UUID paymentIdOf(Invoice invoice) {
        String reference = invoice.reference();
        if (reference == null) {
            return null;
        }
        try {
            return UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
