package dev.tkachuk.acquirer;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

/** Decides as a card's bank would, by test card numbers like the ones payment providers publish. */
final class Issuer {

    /** Approved, but the answer only comes after sim.slow-delay, as if the network were slow. */
    static final String SLOW_CARD = "4000000000000259";

    /** The bank wants the cardholder to pass a 3-D Secure challenge before it decides. */
    static final String CHALLENGE_CARD = "4000000000003220";

    /** The cardholder failed the challenge. */
    static final String AUTHENTICATION_FAILED = "authentication_failed";

    /** The challenge was left open past sim.challenge-ttl. */
    static final String AUTHENTICATION_EXPIRED = "authentication_expired";

    /** Approved, but every refund of it is declined, as for a card whose account was closed since. */
    static final String REFUND_DECLINED_CARD = "4000000000005126";

    /** The code a declined refund comes with. */
    static final String REFUND_DECLINED = "refund_declined";

    /** How the refunds of a charge go, decided by its card when it is charged. */
    enum RefundBehaviour {
        /** Refunded at once. */
        NORMAL,
        /** Declined with refund_declined. */
        DECLINED,
        /** Refunded, but the answer comes after sim.slow-delay, as the slow card's charge does. */
        SLOW
    }

    private static final Map<String, String> DECLINED_CARDS = Map.of(
            "4000000000000002", "do_not_honor",
            "4000000000009995", "insufficient_funds",
            "4000000000000119", "processing_error");

    private Issuer() {
    }

    /** The decline code, or empty if the charge is approved. Any card not listed here is approved. */
    static Optional<String> declineCode(ChargeRequest.Card card, LocalDate today) {
        if (YearMonth.of(card.expYear(), card.expMonth()).isBefore(YearMonth.from(today))) {
            return Optional.of("expired_card");
        }
        if (card.cvc().equals("000")) {
            return Optional.of("incorrect_cvc");
        }
        return Optional.ofNullable(DECLINED_CARDS.get(card.number()));
    }

    static RefundBehaviour refundBehaviour(ChargeRequest.Card card) {
        return switch (card.number()) {
            case REFUND_DECLINED_CARD -> RefundBehaviour.DECLINED;
            case SLOW_CARD -> RefundBehaviour.SLOW;
            default -> RefundBehaviour.NORMAL;
        };
    }

    /** Whether the cardholder has to pass a challenge first; a card declined outright never gets one. */
    static boolean needsChallenge(ChargeRequest.Card card) {
        return card.number().equals(CHALLENGE_CARD);
    }
}
