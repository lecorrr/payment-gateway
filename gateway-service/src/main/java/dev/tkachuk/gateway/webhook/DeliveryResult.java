package dev.tkachuk.gateway.webhook;

import org.jspecify.annotations.Nullable;

/** How one attempt to send a webhook went; error says why it wasn't delivered, without the response body. */
record DeliveryResult(Outcome outcome, @Nullable String error) {

    enum Outcome {
        /** Any 2xx. */
        DELIVERED,
        /** Anything else, a redirect, timeout or refused connection included: tried again later. */
        FAILED,
        /** 410 Gone: the endpoint says it is gone for good, so the event is not tried again. */
        GONE
    }

    static DeliveryResult delivered() {
        return new DeliveryResult(Outcome.DELIVERED, null);
    }

    static DeliveryResult failed(String error) {
        return new DeliveryResult(Outcome.FAILED, error);
    }

    static DeliveryResult gone() {
        return new DeliveryResult(Outcome.GONE, "HTTP 410");
    }
}
