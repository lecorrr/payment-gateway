package dev.tkachuk.gateway.provider;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a provider said about a charge. The code is the provider's decline code for DECLINED and one of
 * {@link FailureCodes} for FAILED.
 */
public record ProviderResult(Outcome outcome, @Nullable String providerReference, @Nullable String code) {

    public enum Outcome {
        /** The card was charged. */
        APPROVED,
        /** The provider or the card's bank refused the charge. */
        DECLINED,
        /** The card was not charged and won't be: the provider never got the request, or refused the request itself. */
        FAILED,
        /** From a lookup only: the provider has no charge with this reference. */
        NOT_FOUND,
        /** No answer, or one that can't be read: the card may or may not have been charged. */
        UNKNOWN
    }

    public ProviderResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static ProviderResult approved(String providerReference) {
        return new ProviderResult(Outcome.APPROVED, providerReference, null);
    }

    public static ProviderResult declined(String providerReference, @Nullable String declineCode) {
        return new ProviderResult(Outcome.DECLINED, providerReference, declineCode);
    }

    public static ProviderResult failed(String failureCode) {
        return new ProviderResult(Outcome.FAILED, null, failureCode);
    }

    public static ProviderResult notFound() {
        return new ProviderResult(Outcome.NOT_FOUND, null, null);
    }

    public static ProviderResult unknown() {
        return new ProviderResult(Outcome.UNKNOWN, null, null);
    }
}
