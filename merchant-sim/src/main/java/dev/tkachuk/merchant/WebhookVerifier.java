package dev.tkachuk.merchant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Checks a webhook the way Standard Webhooks tells receivers to: its timestamp is close to now, and one of its
 * signatures is "v1," and the Base64 HMAC-SHA256 of "{webhook-id}.{webhook-timestamp}.{body}", keyed with the
 * secret's bytes. The body is checked as the bytes that came, before anything parses it.
 */
final class WebhookVerifier {

    static final String SECRET_PREFIX = "whsec_";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_VERSION = "v1,";

    private final byte[] key;
    private final Duration tolerance;

    /** Throws IllegalArgumentException for a secret that is not "whsec_" and 24 to 64 bytes in Base64. */
    WebhookVerifier(String secret, Duration tolerance) {
        this.key = keyOf(secret);
        this.tolerance = tolerance;
    }

    /** Returns for a webhook that can be trusted; otherwise throws InvalidWebhookException saying why not. */
    void verify(@Nullable String messageId, @Nullable String timestamp, @Nullable String signatures, byte[] body,
            Instant now) {
        if (messageId == null || messageId.isBlank() || timestamp == null || signatures == null) {
            throw new InvalidWebhookException("webhook-id, webhook-timestamp and webhook-signature are all needed");
        }
        Instant sentAt;
        try {
            sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException | DateTimeException e) {
            throw new InvalidWebhookException("webhook-timestamp is not a time in Unix seconds");
        }
        if (Duration.between(sentAt, now).abs().compareTo(tolerance) > 0) {
            throw new InvalidWebhookException("webhook-timestamp is more than " + tolerance + " from now");
        }
        byte[] expected = sign(messageId, timestamp, body);
        // Several signatures come while the sender rotates its secret; one that matches is enough.
        for (String signature : signatures.split(" ")) {
            if (signature.startsWith(SIGNATURE_VERSION)
                    && MessageDigest.isEqual(expected, decode(signature.substring(SIGNATURE_VERSION.length())))) {
                return;
            }
        }
        throw new InvalidWebhookException("no signature matches the body");
    }

    private byte[] sign(String messageId, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    // A signature that is not Base64 matches nothing.
    private static byte[] decode(String signature) {
        try {
            return Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static byte[] keyOf(String secret) {
        if (secret == null || !secret.startsWith(SECRET_PREFIX)) {
            throw new IllegalArgumentException("merchant-sim.webhook-secret must start with " + SECRET_PREFIX);
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            // Not passed on: the decoder's message quotes a character of the secret.
            throw new IllegalArgumentException("merchant-sim.webhook-secret must be " + SECRET_PREFIX
                    + " followed by Base64");
        }
        if (key.length < 24 || key.length > 64) {
            throw new IllegalArgumentException("merchant-sim.webhook-secret must have 24 to 64 bytes after "
                    + SECRET_PREFIX + ", this one has " + key.length);
        }
        return key;
    }
}
