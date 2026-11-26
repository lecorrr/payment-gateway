package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.acquirer.ChargeService.Kind;
import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The 3-D Secure challenge of a charge, with a clock the test moves, and the changes it publishes. */
class ChargeServiceTest {

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String RETURN_URL = "http://gateway.test/3ds/return/ref-1";

    private final TestClock clock = new TestClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final SimProperties properties = new SimProperties("test-acquirer-key-0123456789",
            "http://sim.test", Duration.ZERO, TTL, 100, Duration.ofSeconds(2));
    private final List<Object> events = new ArrayList<>();
    private final ChargeService charges = new ChargeService(new ChargeStore(properties), properties, clock,
            events::add);

    @Test
    void shouldWaitForChallengeAtItsPage() {
        Charge charge = challengeCharge("ref-1").charge();

        assertThat(charge.status()).isEqualTo(Charge.REQUIRES_ACTION);
        assertThat(charge.actionUrl()).isEqualTo("http://sim.test/challenge/" + charge.id());
        assertThat(charge.authCode()).isNull();
        assertThat(charge.declineCode()).isNull();
        assertThat(charges.findById(charge.id()).orElseThrow().returnUrl()).isEqualTo(RETURN_URL);
    }

    @Test
    void shouldApproveWhenChallengeIsPassed() {
        Charge charge = challengeCharge("ref-1").charge();

        StoredCharge completed = charges.completeChallenge(charge.id(), true).orElseThrow();

        assertThat(completed.charge().status()).isEqualTo(Charge.APPROVED);
        assertThat(completed.charge().authCode()).matches("[0-9]{6}");
        assertThat(completed.charge().actionUrl()).isNull();
        assertThat(completed.returnUrl()).isEqualTo(RETURN_URL);
        assertThat(charges.find("ref-1")).contains(completed.charge());
    }

    @Test
    void shouldDeclineWhenChallengeIsFailed() {
        Charge charge = challengeCharge("ref-1").charge();

        Charge completed = charges.completeChallenge(charge.id(), false).orElseThrow().charge();

        assertThat(completed.status()).isEqualTo(Charge.DECLINED);
        assertThat(completed.declineCode()).isEqualTo(Issuer.AUTHENTICATION_FAILED);
    }

    @Test
    void shouldKeepFirstOutcomeOfChallenge() {
        Charge charge = challengeCharge("ref-1").charge();
        charges.completeChallenge(charge.id(), false);

        Charge second = charges.completeChallenge(charge.id(), true).orElseThrow().charge();

        assertThat(second.status()).isEqualTo(Charge.DECLINED);
    }

    @Test
    void shouldDeclineChallengeLeftOpenTooLong() {
        Charge charge = challengeCharge("ref-1").charge();
        clock.advance(TTL.minusSeconds(1));
        assertThat(charges.find("ref-1").orElseThrow().status()).isEqualTo(Charge.REQUIRES_ACTION);

        clock.advance(Duration.ofSeconds(1));

        Charge expired = charges.find("ref-1").orElseThrow();
        assertThat(expired.status()).isEqualTo(Charge.DECLINED);
        assertThat(expired.declineCode()).isEqualTo(Issuer.AUTHENTICATION_EXPIRED);
        assertThat(charges.completeChallenge(charge.id(), true).orElseThrow().charge()).isEqualTo(expired);
    }

    @Test
    void shouldExpireChallengeCompletedTooLate() {
        Charge charge = challengeCharge("ref-1").charge();
        clock.advance(TTL);

        Charge completed = charges.completeChallenge(charge.id(), true).orElseThrow().charge();

        assertThat(completed.declineCode()).isEqualTo(Issuer.AUTHENTICATION_EXPIRED);
    }

    @Test
    void shouldReplayChargeAsItIsNow() {
        Charge charge = challengeCharge("ref-1").charge();
        charges.completeChallenge(charge.id(), true);

        ChargeService.Result replay = charges.charge(request("ref-1", Issuer.CHALLENGE_CARD, "123"));

        assertThat(replay.kind()).isEqualTo(Kind.REPLAYED);
        assertThat(replay.charge().status()).isEqualTo(Charge.APPROVED);
    }

    @Test
    void shouldDeclineChallengeCardOutrightWhenBankWould() {
        Charge charge = charges.charge(request("ref-1", Issuer.CHALLENGE_CARD, "000")).charge();

        assertThat(charge.status()).isEqualTo(Charge.DECLINED);
        assertThat(charge.declineCode()).isEqualTo("incorrect_cvc");
        assertThat(charge.actionUrl()).isNull();
    }

    @Test
    void shouldPublishOutcomeOfChallenge() {
        Charge charge = challengeCharge("ref-1").charge();

        Charge completed = charges.completeChallenge(charge.id(), true).orElseThrow().charge();

        assertThat(events).containsExactly(new ChargeChanged(completed));
    }

    @Test
    void shouldPublishChallengeThatRanOutOnce() {
        challengeCharge("ref-1");
        clock.advance(TTL);

        Charge expired = charges.find("ref-1").orElseThrow();
        charges.find("ref-1");
        charges.completeChallenge(expired.id(), true);

        assertThat(events).containsExactly(new ChargeChanged(expired));
    }

    @Test
    void shouldPublishNothingWhenChallengeIsOverAlready() {
        Charge charge = challengeCharge("ref-1").charge();
        charges.completeChallenge(charge.id(), false);

        charges.completeChallenge(charge.id(), true);

        assertThat(events).hasSize(1);
    }

    @Test
    void shouldPublishNothingForChargeDecidedAtOnce() {
        charges.charge(request("ref-1", "4111111111111111", "123"));

        assertThat(events).isEmpty();
    }

    @Test
    void shouldListChargesSinceTimeNewestFirst() {
        charges.charge(request("ref-1", "4111111111111111", "123"));
        clock.advance(Duration.ofMinutes(1));
        charges.charge(request("ref-2", "4111111111111111", "123"));
        clock.advance(Duration.ofMinutes(1));
        charges.charge(request("ref-3", "4000000000000002", "123"));

        assertThat(charges.chargesSince(Instant.parse("2026-11-25T10:01:00Z")))
                .extracting(Charge::reference)
                .containsExactly("ref-3", "ref-2");
    }

    @Test
    void shouldFindNoChallengeForUnknownCharge() {
        assertThat(charges.findById("ch_000000000000000000000000")).isEmpty();
        assertThat(charges.completeChallenge("ch_000000000000000000000000", true)).isEmpty();
    }

    private StoredCharge challengeCharge(String reference) {
        ChargeService.Result result = charges.charge(request(reference, Issuer.CHALLENGE_CARD, "123"));
        assertThat(result.kind()).isEqualTo(Kind.CREATED);
        return charges.findById(result.charge().id()).orElseThrow();
    }

    private static ChargeRequest request(String reference, String number, String cvc) {
        return new ChargeRequest(reference, 15000L, "UAH", new ChargeRequest.Card(number, 12, 2099, cvc, null),
                "Two tickets", RETURN_URL);
    }
}
