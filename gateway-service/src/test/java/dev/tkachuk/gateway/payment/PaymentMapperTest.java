package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.gateway.api.model.CardBrandDto;
import dev.tkachuk.gateway.api.model.CardDetailsDto;
import dev.tkachuk.gateway.api.model.CreatePaymentRequestDto;
import dev.tkachuk.gateway.api.model.CurrencyDto;
import dev.tkachuk.gateway.api.model.NextActionDto;
import dev.tkachuk.gateway.api.model.PaymentEventDto;
import dev.tkachuk.gateway.api.model.PaymentEventTypeDto;
import dev.tkachuk.gateway.api.model.PaymentResponseDto;
import dev.tkachuk.gateway.api.model.PaymentStatusDto;
import dev.tkachuk.gateway.card.CardBrand;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-25T10:00:00Z");
    private static final String ACTION_URL = "http://localhost:8091/challenge/ch_0123456789abcdef01234567";

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
        assertThat(payment.returnUrl()).isNull();
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
    void shouldKeepReturnUrlAsSent() {
        String returnUrl = "https://shop.example.com/checkout/done?order=ORDER-1001&lang=uk";

        NewPayment payment = PaymentMapper.toNewPayment(request("4111 1111 1111 1111").returnUrl(returnUrl));

        assertThat(payment.returnUrl()).isEqualTo(URI.create(returnUrl));
    }

    // The contract's pattern lets these through: no spaces, and the right scheme.
    @ParameterizedTest
    @ValueSource(strings = {"https://shop.example.com/a|b", "https:///no-host", "https://shop.example.com/%zz"})
    void shouldRefuseReturnUrlThatIsNoUrl(String returnUrl) {
        assertThatThrownBy(() -> PaymentMapper.toNewPayment(request("4111 1111 1111 1111").returnUrl(returnUrl)))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.RETURN_URL_INVALID))
                .message().doesNotContain(returnUrl);
    }

    @Test
    void shouldAnswerWithCardSummaryOnly() {
        Payment payment = payment(PaymentStatus.DECLINED, "insufficient_funds", null);

        PaymentResponseDto response = PaymentMapper.toResponse(payment);

        assertThat(response.getId()).isEqualTo(payment.id());
        assertThat(response.getStatus()).isEqualTo(PaymentStatusDto.DECLINED);
        assertThat(response.getDeclineCode()).isEqualTo("insufficient_funds");
        assertThat(response.getProviderReference()).isEqualTo("ch_1");
        assertThat(response.getNextAction()).isNull();
        assertThat(response.getCard().getBrand()).isEqualTo(CardBrandDto.VISA);
        assertThat(response.getCard().getBin()).isEqualTo("411111");
        assertThat(response.getCard().getLast4()).isEqualTo("1111");
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:00:01Z"));
    }

    @Test
    void shouldTellCustomerWhereToGoWhileActionIsRequired() {
        PaymentResponseDto response = PaymentMapper.toResponse(payment(PaymentStatus.REQUIRES_ACTION, null,
                ACTION_URL));

        assertThat(response.getStatus()).isEqualTo(PaymentStatusDto.REQUIRES_ACTION);
        assertThat(response.getNextAction().getType()).isEqualTo(NextActionDto.TypeEnum.REDIRECT);
        assertThat(response.getNextAction().getUrl()).isEqualTo(ACTION_URL);
    }

    @ParameterizedTest
    @CsvSource({
            "REQUIRES_ACTION, payment.requires_action",
            "SUCCEEDED, payment.succeeded",
            "DECLINED, payment.declined",
            "FAILED, payment.failed"})
    void shouldNameEventAfterStatus(PaymentStatus status, String eventType) {
        assertThat(PaymentMapper.eventTypeOf(status).getValue()).isEqualTo(eventType);
    }

    @Test
    void shouldHaveNoEventForProcessing() {
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentMapper.eventTypeOf(PaymentStatus.PROCESSING));
    }

    @Test
    void shouldPutPaymentAsApiShowsItIntoEvent() {
        Payment payment = payment(PaymentStatus.SUCCEEDED, null, null);

        PaymentEventDto event = PaymentMapper.toEvent(payment);

        assertThat(event.getType()).isEqualTo(PaymentEventTypeDto.PAYMENT_SUCCEEDED);
        assertThat(event.getTimestamp()).isEqualTo(OffsetDateTime.parse("2026-11-25T10:00:01Z"));
        assertThat(event.getData()).isEqualTo(PaymentMapper.toResponse(payment));
    }

    private static Payment payment(PaymentStatus status, String declineCode, String actionUrl) {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), "key-1", "f".repeat(64), "ORDER-1001", 15000,
                Currency.UAH, "Two tickets", null, status, CardBrand.VISA, "411111", "1111", "acquirer-sim", "ch_1",
                declineCode, actionUrl, null, CREATED_AT, CREATED_AT.plusSeconds(1));
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
