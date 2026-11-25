package dev.tkachuk.gateway.provider.monobank;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The key monobank signs webhooks with, fetched on first use and kept. A signature that doesn't match it has the key
 * fetched again, in case monobank changed it, but at most every 10 minutes: otherwise every forged webhook would make
 * the gateway call monobank.
 */
@Component
class MonobankPublicKey {

    static final Duration REFETCH_AFTER = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(MonobankPublicKey.class);

    private final MonobankClient client;
    private final Clock clock;

    @Nullable
    private PublicKey key;
    private Instant fetchedAt = Instant.MIN;

    MonobankPublicKey(MonobankClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * Whether monobank signed this body. Throws when that can't be told now: RestClientException when the key can't
     * be fetched, IllegalStateException when monobank's answer holds no usable key.
     */
    synchronized boolean signed(byte[] body, String signature) {
        PublicKey current = key;
        if (current != null) {
            if (MonobankSignatures.isValid(current, body, signature)) {
                return true;
            }
            if (clock.instant().isBefore(fetchedAt.plus(REFETCH_AFTER))) {
                return false;
            }
        }
        return MonobankSignatures.isValid(fetch(), body, signature);
    }

    private PublicKey fetch() {
        String base64Pem = client.publicKey();
        if (base64Pem == null || base64Pem.isBlank()) {
            throw new IllegalStateException("monobank answered GET /api/merchant/pubkey without a key");
        }
        PublicKey fetched = MonobankSignatures.parsePublicKey(base64Pem);
        key = fetched;
        fetchedAt = clock.instant();
        log.info("Fetched the key monobank signs its webhooks with");
        return fetched;
    }
}
