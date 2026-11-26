package dev.tkachuk.gateway.provider;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A connector that answers every charge with the result a test sets, and counts the charges it got. */
final class FakeConnector implements ProviderConnector {

    private final String name;
    private final List<String> missingSettings;

    @Nullable
    private ProviderResult answer;
    private int charges;

    FakeConnector(String name) {
        this(name, List.of());
    }

    FakeConnector(String name, List<String> missingSettings) {
        this.name = name;
        this.missingSettings = missingSettings;
    }

    FakeConnector answering(ProviderResult result) {
        this.answer = result;
        return this;
    }

    int charges() {
        return charges;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> missingSettings() {
        return missingSettings;
    }

    @Override
    public ProviderResult charge(ChargeRequest request) {
        charges++;
        if (answer == null) {
            throw new IllegalStateException(name + " was charged, but the test gave it no answer");
        }
        return answer;
    }

    @Override
    public ProviderResult lookup(ChargeLookup lookup) {
        throw new UnsupportedOperationException();
    }

    // The router never sends refunds: they go to the provider that charged the payment.
    @Override
    public ProviderRefundResult refund(RefundRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProviderRefundResult lookupRefund(RefundLookup lookup) {
        throw new UnsupportedOperationException();
    }
}
