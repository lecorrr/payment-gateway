package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.gateway.api.model.CurrencyDto;
import dev.tkachuk.gateway.api.model.RefundEventDto;
import dev.tkachuk.gateway.api.model.RefundEventTypeDto;
import dev.tkachuk.gateway.api.model.RefundResponseDto;
import dev.tkachuk.gateway.api.model.RefundStatusDto;
import dev.tkachuk.gateway.common.Currency;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RefundMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-25T10:01:00Z");

    @Test
    void shouldHaveSameStatusesInDomainAndApi() {
        assertThat(Arrays.stream(RefundStatus.values()).map(Enum::name).toList())
                .isEqualTo(Arrays.stream(RefundStatusDto.values()).map(Enum::name).toList());
    }

    @Test
    void shouldAnswerWithRefundAsStored() {
        Refund refund = refund(RefundStatus.FAILED, "refund_declined");

        RefundResponseDto response = RefundMapper.toResponse(refund);

        assertThat(response.getId()).isEqualTo(refund.id());
        assertThat(response.getPaymentId()).isEqualTo(refund.paymentId());
        assertThat(response.getAmount()).isEqualTo(5000);
        assertThat(response.getCurrency()).isEqualTo(CurrencyDto.UAH);
        assertThat(response.getStatus()).isEqualTo(RefundStatusDto.FAILED);
        assertThat(response.getProvider()).isEqualTo("monobank");
        assertThat(response.getProviderReference()).isEqualTo("re_1");
        assertThat(response.getFailureCode()).isEqualTo("refund_declined");
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:01:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:01:02Z"));
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED, refund.succeeded", "FAILED, refund.failed"})
    void shouldNameEventAfterOutcome(RefundStatus status, String eventType) {
        assertThat(RefundMapper.eventTypeOf(status).getValue()).isEqualTo(eventType);
    }

    @Test
    void shouldHaveNoEventForProcessing() {
        assertThatThrownBy(() -> RefundMapper.eventTypeOf(RefundStatus.PROCESSING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPutRefundAsApiShowsItIntoEvent() {
        Refund refund = refund(RefundStatus.SUCCEEDED, null);

        RefundEventDto event = RefundMapper.toEvent(refund);

        assertThat(event.getType()).isEqualTo(RefundEventTypeDto.REFUND_SUCCEEDED);
        assertThat(event.getTimestamp()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:01:02Z"));
        assertThat(event.getData()).isEqualTo(RefundMapper.toResponse(refund));
    }

    @Test
    void shouldCallOnlyFinishedStatusesFinal() {
        assertThat(Arrays.stream(RefundStatus.values()).filter(RefundStatus::isFinal).toList())
                .isEqualTo(List.of(RefundStatus.SUCCEEDED, RefundStatus.FAILED));
    }

    private static Refund refund(RefundStatus status, String failureCode) {
        return new Refund(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "refund-1", "f".repeat(64), 5000,
                Currency.UAH, status, "monobank", "re_1", failureCode, null, CREATED_AT, CREATED_AT.plusSeconds(2));
    }
}
