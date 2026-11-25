package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.gateway.api.model.CardBrandDto;
import dev.tkachuk.gateway.api.model.CardDetailsDto;
import dev.tkachuk.gateway.api.model.CreatePaymentRequestDto;
import dev.tkachuk.gateway.api.model.CurrencyDto;
import dev.tkachuk.gateway.api.model.PaymentResponseDto;
import dev.tkachuk.gateway.api.model.PaymentStatusDto;
import dev.tkachuk.gateway.card.CardBrand;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.common.error.GatewayException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentMapperTest {

    @Test
    void shouldHaveSameValuesInDomainAndApiEnums() {
        assertThat(names(PaymentStatus.values())).isEqualTo(names(PaymentStatusDto.values()));
        assertThat(names(Currency.values())).isEqualTo(names(CurrencyDto.values()));
        assertThat(names(CardBrand.values())).isEqualTo(names(CardBrandDto.values()));
    }

    @Test
    void shouldTurnRequestIntoCheckedPayment() {
        NewPayment payment = PaymentMapper.toNewPayment(request("4111 1111 1111 1111"));

        assertThat(payment.orderId()).isEqualTo("ORDER-1001");
        assertThat(payment.amount()).isEqualTo(15000);
        assertThat(payment.currency()).isEqualTo(Currency.UAH);
        assertThat(payment.description()).isEqualTo("Two tickets");
        assertThat(payment.card().number().digits()).isEqualTo("4111111111111111");
        assertThat(payment.card().expiry()).hasToString("12/2030");
        assertThat(payment.card().cvc().value()).isEqualTo("123");
        assertThat(payment.card().holder()).isEqualTo("OLENA KOVALENKO");
    }

    @Test
    void shouldRefuseCardThatFailsLuhnCheck() {
        assertThatThrownBy(() -> PaymentMapper.toNewPayment(request("4111 1111 1111 1112")))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void shouldAnswerWithCardSummaryOnly() {
        Instant createdAt = Instant.parse("2026-11-25T10:00:00Z");
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), "key-1", "f".repeat(64), "ORDER-1001",
                15000, Currency.UAH, "Two tickets", PaymentStatus.DECLINED, CardBrand.VISA, "411111", "1111",
                "acquirer-sim", "ch_1", "insufficient_funds", null, createdAt, createdAt.plusSeconds(1));

        PaymentResponseDto response = PaymentMapper.toResponse(payment);

        assertThat(response.getId()).isEqualTo(payment.id());
        assertThat(response.getStatus()).isEqualTo(PaymentStatusDto.DECLINED);
        assertThat(response.getDeclineCode()).isEqualTo("insufficient_funds");
        assertThat(response.getProviderReference()).isEqualTo("ch_1");
        assertThat(response.getCard().getBrand()).isEqualTo(CardBrandDto.VISA);
        assertThat(response.getCard().getBin()).isEqualTo("411111");
        assertThat(response.getCard().getLast4()).isEqualTo("1111");
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:00:01Z"));
    }

    private static CreatePaymentRequestDto request(String cardNumber) {
        return new CreatePaymentRequestDto()
                .orderId("ORDER-1001")
                .amount(15000L)
                .currency(CurrencyDto.UAH)
                .description("Two tickets")
                .card(new CardDetailsDto()
                        .number(cardNumber)
                        .expiryMonth(12)
                        .expiryYear(2030)
                        .cvc("123")
                        .holder("OLENA KOVALENKO"));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
