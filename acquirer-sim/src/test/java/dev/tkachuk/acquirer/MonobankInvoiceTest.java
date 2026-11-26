package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MonobankInvoiceTest {

    private static final Instant CREATED = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void shouldShowApprovedChargeAsSuccess() {
        MonobankInvoice invoice = MonobankInvoice.of(charge(Charge.APPROVED, null, null), List.of());

        assertThat(invoice.status()).isEqualTo("success");
        assertThat(invoice.errCode()).isNull();
        assertThat(invoice.failureReason()).isNull();
        assertThat(invoice.finalAmount()).isEqualTo(15000);
        assertThat(invoice.ccy()).isEqualTo(980);
        assertThat(invoice.invoiceId()).isEqualTo("ch_1");
        assertThat(invoice.reference()).isEqualTo("ref-1");
        assertThat(invoice.createdDate()).isEqualTo(CREATED);
    }

    // The codes from monobank's list of payment errors that the gateway turns back into these decline codes.
    @ParameterizedTest
    @CsvSource({"do_not_honor, 6", "insufficient_funds, 59", "incorrect_cvc, 80", "expired_card, 51",
            "processing_error, 1000", "authentication_failed, 1054", "some_new_code, 6"})
    void shouldShowDeclineAsFailureWithMonobanksErrCode(String declineCode, String errCode) {
        MonobankInvoice invoice = MonobankInvoice.of(charge(Charge.DECLINED, declineCode, null), List.of());

        assertThat(invoice.status()).isEqualTo("failure");
        assertThat(invoice.errCode()).isEqualTo(errCode);
        assertThat(invoice.failureReason()).isNotBlank();
        assertThat(invoice.finalAmount()).isZero();
    }

    @Test
    void shouldShowChallengeLeftOpenAsExpired() {
        MonobankInvoice invoice = MonobankInvoice.of(charge(Charge.DECLINED, Issuer.AUTHENTICATION_EXPIRED, null),
                List.of());

        assertThat(invoice.status()).isEqualTo("expired");
        assertThat(invoice.errCode()).isNull();
    }

    @Test
    void shouldAnswerPaymentDirectWithChallengeUrlAndWithoutErrCode() {
        String actionUrl = "http://localhost:8091/challenge/ch_1";

        MonobankInvoice waiting = MonobankInvoice.paymentDirectAnswer(charge(Charge.REQUIRES_ACTION, null, actionUrl));
        MonobankInvoice declined = MonobankInvoice.paymentDirectAnswer(charge(Charge.DECLINED, "do_not_honor", null));

        assertThat(waiting.status()).isEqualTo("processing");
        assertThat(waiting.tdsUrl()).isEqualTo(actionUrl);
        assertThat(MonobankInvoice.of(charge(Charge.REQUIRES_ACTION, null, actionUrl), List.of()).tdsUrl()).isNull();
        assertThat(declined.errCode()).isNull();
        assertThat(declined.failureReason()).isNotBlank();
    }

    @Test
    void shouldListRefundsAndTakeSucceededOnesFromFinalAmount() {
        Charge charge = charge(Charge.APPROVED, null, null);

        MonobankInvoice invoice = MonobankInvoice.of(charge, List.of(refund("refund-1", 5000, Refund.SUCCEEDED),
                refund("refund-2", 3000, Refund.PENDING), refund("refund-3", 2000, Refund.FAILED)));

        assertThat(invoice.status()).isEqualTo("success");
        assertThat(invoice.finalAmount()).isEqualTo(10000);
        assertThat(invoice.cancelList()).extracting(MonobankInvoice.CancelItem::status)
                .containsExactly("success", "processing", "failure");
        assertThat(invoice.cancelList()).extracting(MonobankInvoice.CancelItem::extRef)
                .containsExactly("refund-1", "refund-2", "refund-3");
        assertThat(invoice.cancelList().getFirst().ccy()).isEqualTo(980);
    }

    @Test
    void shouldShowInvoiceRefundedInFullAsReversed() {
        MonobankInvoice invoice = MonobankInvoice.of(charge(Charge.APPROVED, null, null),
                List.of(refund("refund-1", 10000, Refund.SUCCEEDED), refund("refund-2", 5000, Refund.SUCCEEDED)));

        assertThat(invoice.status()).isEqualTo("reversed");
        assertThat(invoice.finalAmount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"980, UAH", "840, USD", "978, EUR"})
    void shouldTakeCurrenciesByIsoNumber(int ccy, String currency) {
        assertThat(MonobankInvoice.currencyOf(ccy)).contains(currency);
    }

    @Test
    void shouldRefuseCurrencyItDoesNotTake() {
        assertThat(MonobankInvoice.currencyOf(643)).isEmpty();
    }

    private static Refund refund(String reference, long amount, String status) {
        String failureCode = status.equals(Refund.FAILED) ? Issuer.REFUND_DECLINED : null;
        return new Refund("re_" + reference, "ch_1", reference, amount, "UAH", status, failureCode, CREATED,
                CREATED.plusSeconds(2));
    }

    private static Charge charge(String status, String declineCode, String actionUrl) {
        return new Charge("ch_1", "ref-1", status, declineCode, null, actionUrl, 15000, "UAH", CREATED);
    }
}
