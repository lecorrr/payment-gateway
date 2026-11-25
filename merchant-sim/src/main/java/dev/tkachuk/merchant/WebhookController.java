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
 * POST /webhooks takes the gateway's webhooks: the signature is checked on the raw body first, then the event is
 * taken once per webhook-id. GET /events lists what came.
 */
@RestController
class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /** The parts of the gateway's PaymentEvent the shop reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentEvent(String type, Instant timestamp, Payment data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Payment(String id, String status) {
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
        PaymentEvent event;
        try {
            event = jsonMapper.readValue(body, PaymentEvent.class);
        } catch (JacksonException e) {
            log.warn("Webhook {} refused: its body is no payment event", messageId);
            return text(HttpStatus.BAD_REQUEST, "The body is no payment event");
        }
        if (event.data() == null) {
            return text(HttpStatus.BAD_REQUEST, "The event has no payment");
        }
        boolean taken = events.take(messageId, event.type(), event.data().id(), event.data().status(),
                event.timestamp(), clock.instant());
        if (taken) {
            log.info("Webhook {} {}: payment {} is {}", messageId, event.type(), event.data().id(),
                    event.data().status());
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
