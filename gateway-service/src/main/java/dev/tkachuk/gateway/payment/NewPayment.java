package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.common.Currency;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * A merchant's payment request, checked. The amount is in minor units; returnUrl is where the customer's browser
 * goes after a 3-D Secure challenge.
 */
public record NewPayment(String orderId, long amount, Currency currency, @Nullable String description,
        @Nullable URI returnUrl, Card card) {
}
