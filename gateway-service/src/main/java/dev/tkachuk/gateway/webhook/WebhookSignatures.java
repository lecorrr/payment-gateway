package dev.tkachuk.gateway.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standard Webhooks signatures: "v1," and the Base64 of an HMAC-SHA256 over "{webhook-id}.{webhook-timestamp}.{body}",
 * keyed with the secret's bytes. The body is signed as the exact bytes sent, so the receiver must check it before
 * parsing it.
 */
final class WebhookSignatures {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignatures() {
    }

    static String sign(WebhookSecret secret, String messageId, long timestampSeconds, byte[] body) {
        Mac mac = newMac(secret);
        mac.update((messageId + "." + timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    // A new Mac per call, since a Mac is not thread-safe.
    private static Mac newMac(WebhookSecret secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.key(), ALGORITHM));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}
