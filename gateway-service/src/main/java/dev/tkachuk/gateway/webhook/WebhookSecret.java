package dev.tkachuk.gateway.webhook;

import java.util.Base64;

/**
 * A webhook endpoint's signing secret in the Standard Webhooks format: "whsec_" and 24 to 64 random bytes in
 * Base64. The decoded bytes, not the text, are the HMAC key. Never printed: toString() masks it.
 */
public final class WebhookSecret {

    static final String PREFIX = "whsec_";
    static final int MIN_BYTES = 24;
    static final int MAX_BYTES = 64;

    private final String value;
    private final byte[] key;

    private WebhookSecret(String value, byte[] key) {
        this.value = value;
        this.key = key;
    }

    /** Throws IllegalArgumentException for anything but a whsec_ secret; the message never contains the value. */
    public static WebhookSecret parse(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("A webhook secret must start with " + PREFIX);
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            // Not passed on: the decoder's message quotes a character of the secret.
            throw new IllegalArgumentException("A webhook secret must be " + PREFIX + " followed by Base64");
        }
        if (key.length < MIN_BYTES || key.length > MAX_BYTES) {
            throw new IllegalArgumentException("A webhook secret must have " + MIN_BYTES + " to " + MAX_BYTES
                    + " bytes after " + PREFIX + ", this one has " + key.length);
        }
        return new WebhookSecret(value, key);
    }

    /** The secret as the merchant got it, and as it is stored. */
    public String value() {
        return value;
    }

    byte[] key() {
        return key.clone();
    }

    @Override
    public String toString() {
        return PREFIX + "***";
    }
}
