package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.Card;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A keyed hash (HMAC-SHA256) of everything a payment request says except the CVC. Stored with the payment, it tells
 * a retry from a different request sent with the same Idempotency-Key without storing the card number; PCI DSS
 * allows keyed hashes of the whole PAN. The CVC stays out: it must not be kept in any form.
 */
@Component
class RequestFingerprints {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    RequestFingerprints(PaymentProperties properties) {
        this.key = new SecretKeySpec(properties.fingerprintKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    String of(NewPayment payment) {
        Card card = payment.card();
        StringBuilder input = new StringBuilder();
        append(input, payment.orderId());
        append(input, Long.toString(payment.amount()));
        append(input, payment.currency().name());
        append(input, payment.description());
        append(input, card.number().digits());
        append(input, card.expiry().toString());
        append(input, card.holder());
        byte[] hash = newMac().doFinal(input.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    // Each value as its length and the value, so no two different requests give the same input; "-" for none.
    private static void append(StringBuilder input, @Nullable String value) {
        if (value == null) {
            input.append('-');
        } else {
            input.append(value.length()).append(':').append(value);
        }
        input.append(';');
    }

    // A new Mac per call, since a Mac is not thread-safe.
    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}
