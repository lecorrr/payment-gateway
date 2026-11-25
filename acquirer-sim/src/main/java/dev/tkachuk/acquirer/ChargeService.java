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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The charges: decided by Issuer, kept in ChargeStore. A charge whose status changes after its first answer, when its
 * 3-D Secure challenge ends or runs out, is published as ChargeChanged.
 */
@Service
class ChargeService {

    enum Kind { CREATED, REPLAYED, CONFLICT }

    record Result(Kind kind, Charge charge) {
    }

    static final String CHALLENGE_PATH = "/challenge/";

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final ChargeStore store;
    private final SimProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    ChargeService(ChargeStore store, SimProperties properties, Clock clock, ApplicationEventPublisher events) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Decides and stores the charge. A repeat of the request with the same reference gets the stored charge as it is
     * now; the same reference with a different request is a CONFLICT. The slow test card is stored first and
     * answered late, so a caller that gave up waiting can still find it. The challenge card waits for its 3-D Secure
     * challenge at action_url.
     */
    Result charge(ChargeRequest request) {
        Instant now = now();
        Optional<String> declineCode = Issuer.declineCode(request.card(), LocalDate.ofInstant(now, ZoneOffset.UTC));
        boolean challenge = declineCode.isEmpty() && Issuer.needsChallenge(request.card());
        String id = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Charge charge;
        if (declineCode.isPresent()) {
            charge = new Charge(id, request.reference(), Charge.DECLINED, declineCode.get(), null, null,
                    request.amount(), request.currency(), now);
        } else if (challenge) {
            charge = new Charge(id, request.reference(), Charge.REQUIRES_ACTION, null, null,
                    properties.publicUrl() + CHALLENGE_PATH + id, request.amount(), request.currency(), now);
        } else {
            charge = new Charge(id, request.reference(), Charge.APPROVED, null, newAuthCode(), null,
                    request.amount(), request.currency(), now);
        }
        StoredCharge toStore = challenge
                ? new StoredCharge(charge, hashOf(request), request.returnUrl(), now.plus(properties.challengeTtl()))
                : new StoredCharge(charge, hashOf(request), null, null);

        StoredCharge stored = store.putIfAbsent(toStore);
        if (stored != toStore) {
            Kind kind = stored.requestHash().equals(toStore.requestHash()) ? Kind.REPLAYED : Kind.CONFLICT;
            return new Result(kind, current(stored).charge());
        }
        log.info("Charge {} for reference {}: {}{}", charge.id(), charge.reference(), charge.status(),
                declineCode.map(code -> " (" + code + ")").orElse(""));
        if (request.card().number().equals(Issuer.SLOW_CARD)) {
            waitSlowDelay();
        }
        return new Result(Kind.CREATED, charge);
    }

    /** The charge with this reference as it is now: a challenge left open past its time is declined first. */
    Optional<Charge> find(String reference) {
        return store.find(reference).map(this::current).map(StoredCharge::charge);
    }

    /** The same, by the charge's id, with its return URL: for the challenge page. */
    Optional<StoredCharge> findById(String chargeId) {
        return store.findById(chargeId).map(this::current);
    }

    /** The charges made at from or later, as they are now, the newest first. */
    List<Charge> chargesSince(Instant from) {
        return store.createdSince(from).stream().map(this::current).map(StoredCharge::charge).toList();
    }

    /**
     * The cardholder passed or failed the challenge. A charge that is no longer waiting stays as it is, so the
     * first outcome stands; empty if no charge has this id.
     */
    Optional<StoredCharge> completeChallenge(String chargeId, boolean passed) {
        Optional<StoredCharge> found = store.findById(chargeId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AtomicReference<Charge> changed = new AtomicReference<>();
        Optional<StoredCharge> result = store.update(found.get().charge().reference(), stored -> {
            if (isExpired(stored)) {
                return expire(stored, changed);
            }
            if (!stored.charge().waitsForChallenge()) {
                return stored;
            }
            Charge charge = passed
                    ? stored.charge().approved(newAuthCode())
                    : stored.charge().declined(Issuer.AUTHENTICATION_FAILED);
            log.info("Charge {}: challenge {}, {}", charge.id(), passed ? "passed" : "failed", charge.status());
            changed.set(charge);
            return stored.with(charge);
        });
        publish(changed);
        return result;
    }

    private StoredCharge current(StoredCharge stored) {
        if (!isExpired(stored)) {
            return stored;
        }
        AtomicReference<Charge> changed = new AtomicReference<>();
        // Under the store's lock, where the charge may have been finished since it was read.
        StoredCharge now = store.update(stored.charge().reference(),
                latest -> isExpired(latest) ? expire(latest, changed) : latest).orElse(stored);
        publish(changed);
        return now;
    }

    // After the store's lock is let go: listeners don't hold up other requests.
    private void publish(AtomicReference<Charge> changed) {
        Charge charge = changed.get();
        if (charge != null) {
            events.publishEvent(new ChargeChanged(charge));
        }
    }

    private StoredCharge expire(StoredCharge stored, AtomicReference<Charge> changed) {
        log.info("Charge {}: challenge left open past {}, declined", stored.charge().id(), properties.challengeTtl());
        Charge charge = stored.charge().declined(Issuer.AUTHENTICATION_EXPIRED);
        changed.set(charge);
        return stored.with(charge);
    }

    private boolean isExpired(StoredCharge stored) {
        return stored.charge().waitsForChallenge() && !now().isBefore(stored.challengeExpiresAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String newAuthCode() {
        return "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
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
