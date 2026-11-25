package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.api.model.CardBrandDto;
import dev.tkachuk.gateway.api.model.CardDetailsDto;
import dev.tkachuk.gateway.api.model.CardSummaryDto;
import dev.tkachuk.gateway.api.model.CreatePaymentRequestDto;
import dev.tkachuk.gateway.api.model.CurrencyDto;
import dev.tkachuk.gateway.api.model.NextActionDto;
import dev.tkachuk.gateway.api.model.PaymentEventDto;
import dev.tkachuk.gateway.api.model.PaymentEventTypeDto;
import dev.tkachuk.gateway.api.model.PaymentResponseDto;
import dev.tkachuk.gateway.api.model.PaymentStatusDto;
import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.card.Cvc;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** Between the generated API types and the domain. Enums map by name; PaymentMapperTest keeps both sides equal. */
final class PaymentMapper {

    private PaymentMapper() {
    }

    /** Bean validation has checked the request's shape; this checks the card and the return URL themselves. */
    static NewPayment toNewPayment(CreatePaymentRequestDto request) {
        CardDetailsDto card = request.getCard();
        return new NewPayment(
                request.getOrderId(),
                request.getAmount(),
                Currency.valueOf(request.getCurrency().name()),
                request.getDescription(),
                returnUrlOf(request.getReturnUrl()),
                new Card(
                        CardNumber.parse(card.getNumber()),
                        CardExpiry.of(card.getExpiryMonth(), card.getExpiryYear()),
                        new Cvc(card.getCvc()),
                        card.getHolder()));
    }

    static PaymentResponseDto toResponse(Payment payment) {
        return new PaymentResponseDto()
                .id(payment.id())
                .orderId(payment.orderId())
                .amount(payment.amount())
                .currency(CurrencyDto.valueOf(payment.currency().name()))
                .description(payment.description())
                .status(PaymentStatusDto.valueOf(payment.status().name()))
                .nextAction(nextActionOf(payment))
                .card(new CardSummaryDto()
                        .brand(CardBrandDto.valueOf(payment.cardBrand().name()))
                        .bin(payment.cardBin())
                        .last4(payment.cardLast4()))
                .provider(payment.provider())
                .providerReference(payment.providerReference())
                .declineCode(payment.declineCode())
                .createdAt(payment.createdAt().atOffset(ZoneOffset.UTC))
                .updatedAt(payment.updatedAt().atOffset(ZoneOffset.UTC));
    }

    /** The webhook body for the payment's current status, which must be one a webhook reports: not PROCESSING. */
    static PaymentEventDto toEvent(Payment payment) {
        return new PaymentEventDto()
                .type(eventTypeOf(payment.status()))
                .timestamp(payment.updatedAt().atOffset(ZoneOffset.UTC))
                .data(toResponse(payment));
    }

    static PaymentEventTypeDto eventTypeOf(PaymentStatus status) {
        return switch (status) {
            case REQUIRES_ACTION -> PaymentEventTypeDto.PAYMENT_REQUIRES_ACTION;
            case SUCCEEDED -> PaymentEventTypeDto.PAYMENT_SUCCEEDED;
            case DECLINED -> PaymentEventTypeDto.PAYMENT_DECLINED;
            case FAILED -> PaymentEventTypeDto.PAYMENT_FAILED;
            case PROCESSING -> throw new IllegalArgumentException("No webhook reports a payment that is PROCESSING");
        };
    }

    private static @Nullable NextActionDto nextActionOf(Payment payment) {
        if (payment.status() != PaymentStatus.REQUIRES_ACTION) {
            return null;
        }
        return new NextActionDto().type(NextActionDto.TypeEnum.REDIRECT).url(payment.actionUrl());
    }

    // The contract's pattern only checks the scheme and that there are no spaces; this checks the rest is a URL
    // with a host. The value stays out of the message: a return URL can carry the shop's session token.
    private static @Nullable URI returnUrlOf(@Nullable String returnUrl) {
        if (returnUrl == null) {
            return null;
        }
        try {
            URI url = new URI(returnUrl);
            if (url.getHost() == null) {
                throw new URISyntaxException(returnUrl, "no host");
            }
            return url;
        } catch (URISyntaxException e) {
            throw new GatewayException(ErrorCode.RETURN_URL_INVALID, "returnUrl must be an http(s) URL with a host");
        }
    }
}
