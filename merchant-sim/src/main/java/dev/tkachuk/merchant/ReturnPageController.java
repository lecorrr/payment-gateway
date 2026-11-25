package dev.tkachuk.merchant;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The shop's page the customer lands on after 3-D Secure: /return?paymentId=... It shows what the webhooks said,
 * not what the browser brought, and reloads itself every two seconds until the payment's webhook is in.
 */
@RestController
class ReturnPageController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final ReceivedEvents events;

    ReturnPageController(ReceivedEvents events) {
        this.events = events;
    }

    @GetMapping("/return")
    ResponseEntity<String> show(@RequestParam(required = false) String paymentId) {
        if (paymentId == null || !isUuid(paymentId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(HTML)
                    .body(page("", "<p>This page needs the paymentId the gateway sends it.</p>"));
        }
        Optional<String> status = events.statusOf(paymentId);
        String body = status
                .map(value -> "<p>Payment " + paymentId + ": <b>" + HtmlUtils.htmlEscape(value) + "</b></p>")
                .orElse("<p>Payment " + paymentId + ": waiting for the gateway's webhook...</p>");
        // Until a webhook is in, the page reloads itself.
        String refresh = status.isPresent() ? "" : "<meta http-equiv=\"refresh\" content=\"2\">";
        return ResponseEntity.ok().contentType(HTML).body(page(refresh, body));
    }

    private static String page(String head, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Demo Shop</title>%s</head>
                <body><h1>Demo Shop</h1>
                %s
                <p><a href="/events">Webhooks received</a></p></body>
                </html>
                """.formatted(head, body);
    }

    // Only a UUID in its usual form, so the id is safe to put into the page as it is.
    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
