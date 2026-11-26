package dev.tkachuk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.ledger.JournalEntry.Kind;
import dev.tkachuk.ledger.JournalEntry.Posting;
import dev.tkachuk.ledger.LedgerEvent.PaymentSucceeded;
import dev.tkachuk.ledger.LedgerEvent.RefundSucceeded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Entries are balanced postings: a payment is owed to the merchant by the provider, a refund goes back. */
class JournalEntryTest {

    private static final UUID EVENT = UUID.fromString("6f1c2b7e-3a5d-4b8e-9c0f-1a2b3c4d5e6f");
    private static final UUID PAYMENT = UUID.fromString("0d7f5e2a-8c41-4c1b-9a6e-5b3f2d1c0e9a");
    private static final UUID MERCHANT = UUID.fromString("3b9d6c4e-1f2a-4e5b-8c7d-9e0f1a2b3c4d");
    private static final UUID REFUND = UUID.fromString("4c5d6e7f-8091-4a2b-b3c4-d5e6f7081920");
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-26T09:15:02.481233Z");
    private static final Instant BOOKED_AT = Instant.parse("2026-11-26T09:15:03Z");

    @Test
    void shouldOweMerchantWhatProviderCharged() {
        JournalEntry entry = JournalEntry.of(
                new PaymentSucceeded(EVENT, PAYMENT, MERCHANT, "monobank", "UAH", 15000, OCCURRED_AT), BOOKED_AT);

        assertThat(entry.id()).isEqualTo(EVENT);
        assertThat(entry.kind()).isEqualTo(Kind.PAYMENT);
        assertThat(entry.refundId()).isNull();
        assertThat(entry.amount()).isEqualTo(15000);
        assertThat(entry.postings()).containsExactly(
                new Posting("provider:monobank", 15000),
                new Posting("merchant:" + MERCHANT, -15000));
    }

    @Test
    void shouldTakeRefundBackFromMerchantToProvider() {
        JournalEntry entry = JournalEntry.of(
                new RefundSucceeded(EVENT, REFUND, PAYMENT, MERCHANT, "monobank", "UAH", 5000, OCCURRED_AT), BOOKED_AT);

        assertThat(entry.kind()).isEqualTo(Kind.REFUND);
        assertThat(entry.refundId()).isEqualTo(REFUND);
        assertThat(entry.paymentId()).isEqualTo(PAYMENT);
        assertThat(entry.postings()).containsExactly(
                new Posting("merchant:" + MERCHANT, 5000),
                new Posting("provider:monobank", -5000));
    }

    // From one minor unit up to the most an amount can be: every entry adds up to zero.
    @ParameterizedTest
    @ValueSource(longs = {1, 99, 15000, 1_000_000_000_000L, Long.MAX_VALUE})
    void shouldBalanceWhateverTheAmount(long amount) {
        JournalEntry payment = JournalEntry.of(
                new PaymentSucceeded(EVENT, PAYMENT, MERCHANT, "acquirer-sim", "USD", amount, OCCURRED_AT), BOOKED_AT);
        JournalEntry refund = JournalEntry.of(
                new RefundSucceeded(EVENT, REFUND, PAYMENT, MERCHANT, "acquirer-sim", "USD", amount, OCCURRED_AT),
                BOOKED_AT);

        assertThat(payment.postings()).extracting(Posting::amount).containsExactly(amount, -amount);
        assertThat(refund.postings()).extracting(Posting::amount).containsExactly(amount, -amount);
    }

    @Test
    void shouldRefuseEntryThatDoesNotBalance() {
        assertThatThrownBy(() -> entry(Kind.PAYMENT, null, List.of(new Posting("a", 100), new Posting("b", -99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doesn't balance: its postings add up to 1");
    }

    @Test
    void shouldRefuseEntryWithOnlyOnePosting() {
        assertThatThrownBy(() -> entry(Kind.PAYMENT, null, List.of(new Posting("a", 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a debit and a credit");
    }

    @Test
    void shouldRefuseRefundIdOnPaymentAndPaymentWithoutOne() {
        List<Posting> balanced = List.of(new Posting("a", 100), new Posting("b", -100));

        assertThatThrownBy(() -> entry(Kind.PAYMENT, REFUND, balanced)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(Kind.REFUND, null, balanced)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefusePostingThatMovesNothing() {
        assertThatThrownBy(() -> new Posting("merchant:" + MERCHANT, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moves nothing");
    }

    @Test
    void shouldKeepItsOwnCopyOfPostings() {
        List<Posting> postings = new ArrayList<>(List.of(new Posting("a", 100), new Posting("b", -100)));
        JournalEntry entry = entry(Kind.PAYMENT, null, postings);

        postings.add(new Posting("c", 5));

        assertThat(entry.postings()).hasSize(2);
        assertThatThrownBy(() -> entry.postings().add(new Posting("c", 5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static JournalEntry entry(Kind kind, UUID refundId, List<Posting> postings) {
        return new JournalEntry(EVENT, kind, MERCHANT, PAYMENT, refundId, "monobank", "UAH", 100, OCCURRED_AT,
                BOOKED_AT, postings);
    }
}
