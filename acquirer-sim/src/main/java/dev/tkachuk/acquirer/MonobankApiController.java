package dev.tkachuk.acquirer;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The part of monobank's acquiring API the gateway's monobank connector uses, so the gateway can run with two
 * providers without a real one: payment-direct, the invoice status, cancel (a refund), the statement, and the key
 * webhooks are signed with. Charges are decided by the same test cards and kept with the others; a challenge happens
 * on acquirer-sim's own page. A refund is "processing" for sim.refund-delay, then finished and reported by webhook.
 * Errors are answered in acquirer-sim's format, not monobank's: the gateway reads only their status.
 */
@RestController
@RequestMapping(MonobankApiController.PATH)
class MonobankApiController {

    static final String PATH = "/api/merchant";

    private final ChargeService charges;
    private final RefundService refunds;
    private final MonobankWebhooks webhooks;
    private final MonobankKeys keys;

    MonobankApiController(ChargeService charges, RefundService refunds, MonobankWebhooks webhooks,
            MonobankKeys keys) {
        this.charges = charges;
        this.refunds = refunds;
        this.webhooks = webhooks;
        this.keys = keys;
    }

    /**
     * Charges the card. A repeat with the same reference gets the invoice as it is now. The answer has no errCode
     * for a failure, as monobank's has none: the invoice status does.
     */
    @PostMapping("/invoice/payment-direct")
    ResponseEntity<?> paymentDirect(@Valid @RequestBody MonobankPaymentDirect request) {
        Optional<String> currency = MonobankInvoice.currencyOf(request.ccy());
        if (currency.isEmpty()) {
            return badRequest("ccy must be 980 (UAH), 840 (USD) or 978 (EUR)");
        }
        if (!Urls.hasHost(request.redirectUrl())
                || (request.webHookUrl() != null && !Urls.hasHost(request.webHookUrl()))) {
            return badRequest("redirectUrl and webHookUrl must be http(s) URLs with a host");
        }
        ChargeService.Result result = charges.charge(request.toChargeRequest(currency.get()));
        if (result.kind() == ChargeService.Kind.CONFLICT) {
            return ResponseEntity.badRequest()
                    .body(new SimError("reference_reused", "This reference belongs to a different charge"));
        }
        Charge charge = result.charge();
        if (result.kind() == ChargeService.Kind.CREATED && request.webHookUrl() != null) {
            webhooks.register(charge.id(), request.webHookUrl());
            // A challenge's outcome is sent when it comes (ChargeChanged).
            if (!charge.waitsForChallenge()) {
                webhooks.send(charge);
            }
        }
        return ResponseEntity.ok(MonobankInvoice.paymentDirectAnswer(charge));
    }

    @GetMapping("/invoice/status")
    ResponseEntity<?> status(@RequestParam String invoiceId) {
        Optional<StoredCharge> stored = charges.findById(invoiceId);
        if (stored.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new SimError("not_found", "No invoice has this id"));
        }
        Charge charge = stored.get().charge();
        return ResponseEntity.ok(MonobankInvoice.of(charge, refunds.ofCharge(charge.id())));
    }

    /**
     * Refunds all or part of a paid invoice. A repeat with the same extRef and amount gets the refund as it is now;
     * one without an extRef gets a reference of its own, so it can't be told from a new refund.
     */
    @PostMapping("/invoice/cancel")
    ResponseEntity<?> cancel(@Valid @RequestBody MonobankCancel request) {
        Optional<StoredCharge> stored = charges.findById(request.invoiceId());
        if (stored.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new SimError("not_found", "No invoice has this id"));
        }
        long amount = request.amount() != null ? request.amount() : refunds.leftOf(stored.get().charge());
        if (amount <= 0) {
            return badRequest("Nothing of this invoice is left to cancel");
        }
        String reference = request.extRef() != null ? request.extRef() : "cancel_" + UUID.randomUUID();
        RefundService.Result result = refunds.refund(new RefundRequest(request.invoiceId(), reference, amount), true);
        return switch (result.kind()) {
            case CREATED, REPLAYED -> ResponseEntity.ok(MonobankCancelAnswer.of(result.refund()));
            case CONFLICT -> badRequest("This extRef belongs to a different cancel");
            case NOT_REFUNDABLE -> badRequest("Only a paid invoice can be cancelled");
            case TOO_LARGE -> badRequest("The invoice has less than this left to cancel");
            case NO_CHARGE -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new SimError("not_found", "No invoice has this id"));
        };
    }

    /** The invoices made from the from second on, up to the to second if given; both are Unix times. */
    @GetMapping("/statement")
    MonobankStatement statement(@RequestParam long from, @RequestParam(required = false) Long to) {
        Instant until = to == null ? Instant.MAX : Instant.ofEpochSecond(to);
        List<MonobankStatement.Item> items = charges.chargesSince(Instant.ofEpochSecond(from)).stream()
                .filter(charge -> !charge.createdAt().isAfter(until))
                .map(MonobankStatement.Item::of)
                .toList();
        return new MonobankStatement(items);
    }

    @GetMapping("/pubkey")
    Map<String, String> publicKey() {
        return Map.of("key", keys.publicKeyBase64Pem());
    }

    private static ResponseEntity<SimError> badRequest(String message) {
        return ResponseEntity.badRequest().body(new SimError("invalid_request", message));
    }
}
