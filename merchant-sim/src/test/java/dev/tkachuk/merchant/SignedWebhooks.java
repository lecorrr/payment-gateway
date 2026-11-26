package dev.tkachuk.merchant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Webhook requests as the gateway sends them, signed here with its own code rather than WebhookVerifier's. */
final class SignedWebhooks {

    static final String SECRET = WebhookVerifierTest.SECRET;

    private SignedWebhooks() {
    }

    static MockHttpServletRequestBuilder webhook(String messageId, String body) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET.substring("whsec_".length())), "HmacSHA256"));
        mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        String signature = "v1," + Base64.getEncoder().encodeToString(
                mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return post("/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", messageId)
                .header("webhook-timestamp", Long.toString(timestamp))
                .header("webhook-signature", signature)
                .content(body);
    }

    static String paymentEvent(String type, String paymentId, String status, String changedAt) {
        return """
                {"type": "%s", "timestamp": "%s",
                 "data": {"id": "%s", "orderId": "ORDER-1001", "amount": 15000, "currency": "UAH", "status": "%s"}}"""
                .formatted(type, changedAt, paymentId, status);
    }

    static String refundEvent(String type, String refundId, String paymentId, String status, String changedAt) {
        return """
                {"type": "%s", "timestamp": "%s",
                 "data": {"id": "%s", "paymentId": "%s", "amount": 5000, "currency": "UAH", "status": "%s"}}"""
                .formatted(type, changedAt, refundId, paymentId, status);
    }
}
