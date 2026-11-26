package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.api.model.CurrencyDto;
import dev.tkachuk.gateway.api.model.RefundEventDto;
import dev.tkachuk.gateway.api.model.RefundEventTypeDto;
import dev.tkachuk.gateway.api.model.RefundResponseDto;
import dev.tkachuk.gateway.api.model.RefundStatusDto;
import dev.tkachuk.gateway.event.RefundEventData;
import java.time.ZoneOffset;

/** Between the generated API types and refunds. Enums map by name; RefundMapperTest keeps both sides equal. */
final class RefundMapper {

    private RefundMapper() {
    }

    static RefundResponseDto toResponse(Refund refund) {
        return new RefundResponseDto()
                .id(refund.id())
                .paymentId(refund.paymentId())
                .amount(refund.amount())
                .currency(CurrencyDto.valueOf(refund.currency().name()))
                .status(RefundStatusDto.valueOf(refund.status().name()))
                .provider(refund.provider())
                .providerReference(refund.providerReference())
                .failureCode(refund.failureCode())
                .createdAt(refund.createdAt().atOffset(ZoneOffset.UTC))
                .updatedAt(refund.updatedAt().atOffset(ZoneOffset.UTC));
    }

    /** The webhook body for the refund's outcome; a refund still PROCESSING has none. */
    static RefundEventDto toEvent(Refund refund) {
        return new RefundEventDto()
                .type(eventTypeOf(refund.status()))
                .timestamp(refund.updatedAt().atOffset(ZoneOffset.UTC))
                .data(toResponse(refund));
    }

    /** The data of the refund's event on the payment-events topic, for its outcome. */
    static RefundEventData toEventData(Refund refund) {
        return new RefundEventData(refund.id(), refund.paymentId(), refund.merchantId(), refund.status().name(),
                refund.amount(), refund.currency(), refund.provider(), refund.providerReference(),
                refund.failureCode(), refund.updatedAt());
    }

    static RefundEventTypeDto eventTypeOf(RefundStatus status) {
        return switch (status) {
            case SUCCEEDED -> RefundEventTypeDto.REFUND_SUCCEEDED;
            case FAILED -> RefundEventTypeDto.REFUND_FAILED;
            case PROCESSING -> throw new IllegalArgumentException("No webhook reports a refund that is PROCESSING");
        };
    }
}
