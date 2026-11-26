package dev.tkachuk.gateway.provider;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a provider said about a refund. The code comes with FAILED: the provider's reason, or one of
 * {@link FailureCodes}. A provider that gives refunds ids of their own passes them as providerReference.
 */
public record ProviderRefundResult(Outcome outcome, @Nullable String providerReference, @Nullable String code) {

    public enum Outcome {
        /** The money went back to the card. */
        SUCCEEDED,
        /** Nothing went back and nothing will: the provider refused the refund, or never got the request. */
        FAILED,
        /** The provider has the refund and hasn't finished it yet. */
        PENDING,
        /** From a lookup only: the provider has no refund with this reference. */
        NOT_FOUND,
        /** No answer, or one that can't be read: the money may or may not be on its way back. */
        UNKNOWN
    }

    public ProviderRefundResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.FAILED) != (code != null)) {
            throw new IllegalArgumentException("A code comes with FAILED and with nothing else");
        }
    }

    public static ProviderRefundResult succeeded(@Nullable String providerReference) {
        return new ProviderRefundResult(Outcome.SUCCEEDED, providerReference, null);
    }

    public static ProviderRefundResult failed(@Nullable String providerReference, String code) {
        return new ProviderRefundResult(Outcome.FAILED, providerReference, code);
    }

    public static ProviderRefundResult failed(String code) {
        return failed(null, code);
    }

    public static ProviderRefundResult pending(@Nullable String providerReference) {
        return new ProviderRefundResult(Outcome.PENDING, providerReference, null);
    }

    public static ProviderRefundResult notFound() {
        return new ProviderRefundResult(Outcome.NOT_FOUND, null, null);
    }

    public static ProviderRefundResult unknown() {
        return new ProviderRefundResult(Outcome.UNKNOWN, null, null);
    }
}
