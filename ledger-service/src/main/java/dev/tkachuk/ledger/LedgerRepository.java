package dev.tkachuk.ledger;

import dev.tkachuk.ledger.JournalEntry.Kind;
import dev.tkachuk.ledger.JournalEntry.Posting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tables processed_events, journal_entries and postings. */
@Repository
class LedgerRepository {

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Notes the event as taken, unless it was taken before; returns whether this is the first time. */
    boolean markProcessed(LedgerEvent event, RecordSource source, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_events (event_id, type, payment_id, topic, partition, record_offset,
                            processed_at)
                        VALUES (:eventId, :type, :paymentId, :topic, :partition, :offset, :now)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", event.eventId())
                .param("type", event.type())
                .param("paymentId", event.paymentId())
                .param("topic", source.topic())
                .param("partition", source.partition())
                .param("offset", source.offset())
                .param("now", utc(now))
                .update();
        return inserted == 1;
    }

    void book(JournalEntry entry) {
        jdbc.sql("""
                        INSERT INTO journal_entries (id, kind, merchant_id, payment_id, refund_id, provider, currency,
                            amount, occurred_at, booked_at)
                        VALUES (:id, :kind, :merchantId, :paymentId, :refundId, :provider, :currency, :amount,
                            :occurredAt, :bookedAt)""")
                .param("id", entry.id())
                .param("kind", entry.kind().name())
                .param("merchantId", entry.merchantId())
                .param("paymentId", entry.paymentId())
                .param("refundId", entry.refundId())
                .param("provider", entry.provider())
                .param("currency", entry.currency())
                .param("amount", entry.amount())
                .param("occurredAt", utc(entry.occurredAt()))
                .param("bookedAt", utc(entry.bookedAt()))
                .update();
        for (Posting posting : entry.postings()) {
            jdbc.sql("INSERT INTO postings (entry_id, account, amount) VALUES (:entryId, :account, :amount)")
                    .param("entryId", entry.id())
                    .param("account", posting.account())
                    .param("amount", posting.amount())
                    .update();
        }
    }

    /**
     * The payment's entries with their postings, in the order they happened. Entries of one moment come in the order
     * they were booked, which for one payment is the order of its events on their partition; the id comes last only
     * so that the order is the same on every call.
     */
    List<JournalEntry> entriesOfPayment(UUID paymentId) {
        Map<UUID, Row> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT e.id, e.kind, e.merchant_id, e.payment_id, e.refund_id, e.provider, e.currency,
                            e.amount, e.occurred_at, e.booked_at, p.account, p.amount AS posting_amount
                        FROM journal_entries e JOIN postings p ON p.entry_id = e.id
                        WHERE e.payment_id = :paymentId
                        ORDER BY e.occurred_at, e.booked_at, e.id, p.amount DESC, p.account""")
                .param("paymentId", paymentId)
                .query((ResultSet row) -> {
                    UUID id = row.getObject("id", UUID.class);
                    Row entry = rows.get(id);
                    if (entry == null) {
                        entry = rowOf(row, id);
                        rows.put(id, entry);
                    }
                    entry.postings().add(new Posting(row.getString("account"), row.getLong("posting_amount")));
                });
        return rows.values().stream().map(Row::toEntry).toList();
    }

    /**
     * What is owed to the merchant in each currency, from its account's postings, with the payments and refunds
     * that make it up.
     */
    List<Balance> balancesOf(UUID merchantId) {
        return jdbc.sql("""
                        SELECT e.currency, -sum(p.amount) AS payable,
                            coalesce(sum(e.amount) FILTER (WHERE e.kind = 'PAYMENT'), 0) AS paid,
                            coalesce(sum(e.amount) FILTER (WHERE e.kind = 'REFUND'), 0) AS refunded,
                            count(*) FILTER (WHERE e.kind = 'PAYMENT') AS payments,
                            count(*) FILTER (WHERE e.kind = 'REFUND') AS refunds
                        FROM journal_entries e JOIN postings p ON p.entry_id = e.id AND p.account = :account
                        WHERE e.merchant_id = :merchantId
                        GROUP BY e.currency
                        ORDER BY e.currency""")
                .param("account", JournalEntry.merchantAccount(merchantId))
                .param("merchantId", merchantId)
                .query((row, rowNumber) -> new Balance(row.getString("currency"), row.getLong("payable"),
                        row.getLong("paid"), row.getLong("refunded"), row.getLong("payments"),
                        row.getLong("refunds")))
                .list();
    }

    /** A merchant's balance in one currency, in minor units: payable is paid less refunded. */
    record Balance(String currency, long payable, long paid, long refunded, long payments, long refunds) {
    }

    // An entry as its rows come in: the entry's columns once, a posting per row.
    private record Row(UUID id, Kind kind, UUID merchantId, UUID paymentId, @Nullable UUID refundId, String provider,
            String currency, long amount, Instant occurredAt, Instant bookedAt, List<Posting> postings) {

        JournalEntry toEntry() {
            return new JournalEntry(id, kind, merchantId, paymentId, refundId, provider, currency, amount,
                    occurredAt, bookedAt, postings);
        }
    }

    private static Row rowOf(ResultSet row, UUID id) throws SQLException {
        return new Row(id, Kind.valueOf(row.getString("kind")), row.getObject("merchant_id", UUID.class),
                row.getObject("payment_id", UUID.class), row.getObject("refund_id", UUID.class),
                row.getString("provider"), row.getString("currency"), row.getLong("amount"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("booked_at", OffsetDateTime.class).toInstant(), new ArrayList<>());
    }

    // The PostgreSQL driver binds OffsetDateTime to timestamptz, but not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
