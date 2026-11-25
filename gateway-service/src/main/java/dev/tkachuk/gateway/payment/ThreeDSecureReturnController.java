package dev.tkachuk.gateway.payment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the provider sends the customer's browser after a 3-D Secure challenge. Browsers have no API key, so
 * ApiKeyFilter lets /3ds/ through; the page tells nothing but the status, and sends the browser on to the merchant's
 * returnUrl when the payment has one.
 */
@RestController
class ThreeDSecureReturnController {

    static final String PATH = "/3ds/return/";

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final PaymentService payments;

    ThreeDSecureReturnController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping(PATH + "{paymentId}")
    ResponseEntity<String> returnFromChallenge(@PathVariable UUID paymentId) {
        Payment payment = payments.returnFromChallenge(paymentId);
        if (payment.returnUrl() != null) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(withPaymentId(payment.returnUrl(), payment.id()))
                    .build();
        }
        return ResponseEntity.ok().contentType(HTML).body("""
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Payment %1$s</title></head>
                <body><p>Payment %1$s. You can close this page.</p></body>
                </html>
                """.formatted(payment.status()));
    }

    /**
     * The merchant's URL with paymentId added to its query. Its other parts are kept exactly as they came, so
     * nothing gets encoded twice; PaymentMapper checked the URL when the payment was created.
     */
    static URI withPaymentId(String returnUrl, UUID paymentId) {
        URI url = URI.create(returnUrl);
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        String query = (url.getRawQuery() == null ? "" : url.getRawQuery() + "&") + "paymentId=" + paymentId;
        String fragment = url.getRawFragment() == null ? "" : "#" + url.getRawFragment();
        return URI.create(url.getScheme() + "://" + url.getRawAuthority() + path + "?" + query + fragment);
    }
}
