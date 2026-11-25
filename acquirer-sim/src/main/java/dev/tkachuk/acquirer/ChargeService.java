package dev.tkachuk.acquirer;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ChargeService {

    enum Kind { CREATED, REPLAYED, CONFLICT }

    record Result(Kind kind, Charge charge) {
    }

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final ChargeStore store;
    private final SimProperties properties;
    private final Clock clock;

    ChargeService(ChargeStore store, SimProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Decides and stores the charge. A repeat of the request with the same reference gets the stored charge; the
     * same reference with a different request is a CONFLICT. The slow test card is stored first and answered late,
     * so a caller that gave up waiting can still find it.
     */
    Result charge(ChargeRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Optional<String> declineCode = Issuer.declineCode(request.card(), LocalDate.ofInstant(now, ZoneOffset.UTC));
        Charge charge = new Charge(
                "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                request.reference(),
                declineCode.isPresent() ? Charge.DECLINED : Charge.APPROVED,
                declineCode.orElse(null),
                declineCode.isPresent() ? null : "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                request.amount(),
                request.currency(),
                now);
        String requestHash = hashOf(request);

        StoredCharge stored = store.putIfAbsent(new StoredCharge(charge, requestHash));
        if (stored.charge() != charge) {
            return stored.requestHash().equals(requestHash)
                    ? new Result(Kind.REPLAYED, stored.charge())
                    : new Result(Kind.CONFLICT, stored.charge());
        }
        log.info("Charge {} for reference {}: {}{}", charge.id(), charge.reference(), charge.status(),
                declineCode.map(code -> " (" + code + ")").orElse(""));
        if (request.card().number().equals(Issuer.SLOW_CARD)) {
            waitSlowDelay();
        }
        return new Result(Kind.CREATED, charge);
    }

    Optional<Charge> find(String reference) {
        return store.find(reference);
    }

    private void waitSlowDelay() {
        try {
            Thread.sleep(properties.slowDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // What is charged: the fields have fixed formats, so "|" can't appear in them.
    private static String hashOf(ChargeRequest request) {
        ChargeRequest.Card card = request.card();
        String charged = String.join("|", request.amount().toString(), request.currency(), card.number(),
                card.expMonth().toString(), card.expYear().toString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(charged.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
