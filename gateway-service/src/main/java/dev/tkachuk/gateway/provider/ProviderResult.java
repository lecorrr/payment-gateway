package dev.tkachuk.gateway.provider;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a provider said about a charge. The code is a decline code for DECLINED and one of {@link FailureCodes} for
 * FAILED; actionUrl, only with REQUIRES_ACTION, is where the customer passes the 3-D Secure challenge.
 */
public record ProviderResult(Outcome outcome, @Nullable String providerReference, @Nullable String code,
        @Nullable String actionUrl) {

    public enum Outcome {
        /** The card was charged. */
        APPROVED,
        /** The provider or the card's bank refused the charge. */
        DECLINED,
        /** The card was not charged and won't be: the provider never got the request, or refused the request itself. */
        FAILED,
        /** The card's bank wants the customer to pass a 3-D Secure challenge before it decides. */
        REQUIRES_ACTION,
        /** The provider has the charge and hasn't decided yet; its reference, if any, is worth keeping. */
        PENDING,
        /** From a lookup only: the provider has no charge with this reference. */
        NOT_FOUND,
        /** No answer, or one that can't be read: the card may or may not have been charged. */
        UNKNOWN
    }

    public ProviderResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.REQUIRES_ACTION) != (actionUrl != null)) {
            throw new IllegalArgumentException("An actionUrl comes with REQUIRES_ACTION and with nothing else");
        }
    }

    public static ProviderResult approved(String providerReference) {
        return new ProviderResult(Outcome.APPROVED, providerReference, null, null);
    }

    public static ProviderResult declined(String providerReference, @Nullable String declineCode) {
        return new ProviderResult(Outcome.DECLINED, providerReference, declineCode, null);
    }

    public static ProviderResult failed(String failureCode) {
        return new ProviderResult(Outcome.FAILED, null, failureCode, null);
    }

    public static ProviderResult requiresAction(String providerReference, String actionUrl) {
        return new ProviderResult(Outcome.REQUIRES_ACTION, providerReference, null, actionUrl);
    }

    public static ProviderResult pending(String providerReference) {
        return new ProviderResult(Outcome.PENDING, providerReference, null, null);
    }

    public static ProviderResult notFound() {
        return new ProviderResult(Outcome.NOT_FOUND, null, null, null);
    }

    public static ProviderResult unknown() {
        return new ProviderResult(Outcome.UNKNOWN, null, null, null);
    }

    /** The provider couldn't take the charge: it was unreachable or turned it away unread, so it never got it. */
    public boolean isUnavailable() {
        return outcome == Outcome.FAILED && FailureCodes.PROVIDER_UNAVAILABLE.equals(code);
    }

    /**
     * Whether this tells something about the provider rather than about the charge: no way through, or no answer.
     * A decline or a refused request is the provider working as it should.
     */
    public boolean isProviderFailure() {
        return outcome == Outcome.UNKNOWN || isUnavailable();
    }
}
