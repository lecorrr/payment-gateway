package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.api.model.CardBrandDto;
import dev.tkachuk.gateway.api.model.CardDetailsDto;
import dev.tkachuk.gateway.api.model.CardSummaryDto;
import dev.tkachuk.gateway.api.model.CreatePaymentRequestDto;
import dev.tkachuk.gateway.api.model.CurrencyDto;
import dev.tkachuk.gateway.api.model.PaymentResponseDto;
import dev.tkachuk.gateway.api.model.PaymentStatusDto;
import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.card.Cvc;
import dev.tkachuk.gateway.common.Currency;
import java.time.ZoneOffset;

/** Between the generated API types and the domain. Enums map by name; PaymentMapperTest keeps both sides equal. */
final class PaymentMapper {

    private PaymentMapper() {
    }

    /** Bean validation has checked the request's shape; this checks the card itself. */
    static NewPayment toNewPayment(CreatePaymentRequestDto request) {
        CardDetailsDto card = request.getCard();
        return new NewPayment(
                request.getOrderId(),
                request.getAmount(),
                Currency.valueOf(request.getCurrency().name()),
                request.getDescription(),
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
}
