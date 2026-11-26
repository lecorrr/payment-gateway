package dev.tkachuk.merchant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /webhooks takes the gateway's webhooks, a payment's or a refund's: the signature is checked on the raw body
 * first, then the event is taken once per webhook-id. GET /events lists what came.
 */
@RestController
class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /**
     * The parts of the gateway's PaymentEvent and RefundEvent the shop reads. A refund's data has the id of its
     * payment in paymentId; a payment's has none.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GatewayEvent(String type, Instant timestamp, Data data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Data(String id, String status, String paymentId) {
        }
    }

    private final WebhookVerifier verifier;
    private final ReceivedEvents events;
    private final MerchantSimProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    WebhookController(ReceivedEvents events, MerchantSimProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.verifier = new WebhookVerifier(properties.webhookSecret(), properties.tolerance());
        this.events = events;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @PostMapping("/webhooks")
    ResponseEntity<String> receive(@RequestHeader(value = "webhook-id", required = false) String messageId,
            @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "webhook-signature", required = false) String signatures,
            @RequestBody byte[] body) {
        try {
            verifier.verify(messageId, timestamp, signatures, body, clock.instant());
        } catch (InvalidWebhookException e) {
            log.warn("Webhook {} refused: {}", messageId, e.getMessage());
            return text(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        int delivery = events.countDelivery(messageId);
        if (delivery <= properties.failFirst()) {
            log.info("Webhook {}, delivery {}: answered 503 on purpose (merchant-sim.fail-first is {})", messageId,
                    delivery, properties.failFirst());
            return text(HttpStatus.SERVICE_UNAVAILABLE, "Failing on purpose; try again");
        }
        GatewayEvent event;
        try {
            event = jsonMapper.readValue(body, GatewayEvent.class);
        } catch (JacksonException e) {
            log.warn("Webhook {} refused: its body is no payment or refund event", messageId);
            return text(HttpStatus.BAD_REQUEST, "The body is no payment or refund event");
        }
        if (event.data() == null || event.data().id() == null) {
            return text(HttpStatus.BAD_REQUEST, "The event has no payment or refund");
        }
        GatewayEvent.Data data = event.data();
        boolean refund = data.paymentId() != null;
        String paymentId = refund ? data.paymentId() : data.id();
        String refundId = refund ? data.id() : null;
        boolean taken = events.take(messageId, event.type(), paymentId, refundId, data.status(), event.timestamp(),
                clock.instant());
        if (taken && refund) {
            log.info("Webhook {} {}: refund {} of payment {} is {}", messageId, event.type(), refundId, paymentId,
                    data.status());
        } else if (taken) {
            log.info("Webhook {} {}: payment {} is {}", messageId, event.type(), paymentId, data.status());
        } else {
            log.info("Webhook {} came again, delivery {}: already taken, nothing to do", messageId, delivery);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    List<ReceivedEvents.Event> events() {
        return events.all();
    }

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
