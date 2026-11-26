package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardNumber;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Keyed hashes (HMAC-SHA256); PCI DSS allows them of the whole PAN. The fingerprint covers everything a payment
 * request says except the CVC: stored with the payment, it tells a retry from a different request sent with the same
 * Idempotency-Key without storing the card number. The CVC stays out: it must not be kept in any form. A refund's
 * fingerprint does the same for refund requests. The card token covers the card number alone, so risk-service can
 * count a card's payments without ever seeing the number.
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
        // Only when there is one, so a request without it keeps the fingerprint stored before returnUrl existed,
        // and its replay still matches.
        URI returnUrl = payment.returnUrl();
        if (returnUrl != null) {
            append(input, returnUrl.toString());
        }
        byte[] hash = newMac().doFinal(input.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /** The fingerprint of a refund request: which payment, and how much of it. */
    String ofRefund(UUID paymentId, long amount) {
        StringBuilder input = new StringBuilder("refund;");
        append(input, paymentId.toString());
        append(input, Long.toString(amount));
        byte[] hash = newMac().doFinal(input.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /** The same for every payment with the card; Base64url, 43 characters. */
    String cardToken(CardNumber number) {
        // A fingerprint's input starts with a length or "-", so no card token is ever a request's fingerprint.
        byte[] hash = newMac().doFinal(("card-token;" + number.digits()).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
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
