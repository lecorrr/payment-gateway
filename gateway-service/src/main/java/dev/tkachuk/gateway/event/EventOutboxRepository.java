package dev.tkachuk.gateway.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table event_outbox: the payment-events topic's events, each published once, in seq order. */
@Repository
class EventOutboxRepository {

    // The advisory lock that one publisher at a time holds, whichever gateway instance it runs in; any number would
    // do that nothing else in this database uses.
    private static final long PUBLISHER_LOCK = 7_301_604_420_009L;

    private final JdbcClient jdbc;

    EventOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(OutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO event_outbox (id, payment_id, type, payload, traceparent, created_at)
                        VALUES (:id, :paymentId, :type, :payload, :traceparent, :createdAt)""")
                .param("id", event.id())
                .param("paymentId", event.paymentId())
                .param("type", event.type())
                .param("payload", event.payload())
                .param("traceparent", event.traceparent())
                .param("createdAt", utc(event.createdAt()))
                .update();
    }

    /**
     * Takes the publisher's lock until the current transaction ends, if no other transaction has it: so only one
     * instance publishes at a time, and the events go out in their order.
     */
    boolean lockForPublishing() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)")
                .param("lock", PUBLISHER_LOCK)
                .query(Boolean.class)
                .single();
    }

    /** The oldest events not published yet, at most limit, in the order they were written. */
    List<OutboxEvent> findUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT seq, id, payment_id, type, payload, traceparent, created_at FROM event_outbox
                        WHERE published_at IS NULL
                        ORDER BY seq
                        LIMIT :limit""")
                .param("limit", limit)
                .query(EventOutboxRepository::fromRow)
                .list();
    }

    void markPublished(List<Long> seqs, Instant now) {
        jdbc.sql("UPDATE event_outbox SET published_at = :now WHERE seq IN (:seqs) AND published_at IS NULL")
                .param("now", utc(now))
                .param("seqs", seqs)
                .update();
    }

    /** When the oldest event not published yet was written, if there is one. */
    Optional<Instant> oldestUnpublishedAt() {
        return jdbc.sql("""
                        SELECT created_at FROM event_outbox
                        WHERE published_at IS NULL
                        ORDER BY seq
                        LIMIT 1""")
                .query((row, rowNumber) -> row.getObject("created_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /** Deletes the events published before the cutoff; returns how many. */
    int deletePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM event_outbox WHERE published_at < :cutoff")
                .param("cutoff", utc(cutoff))
                .update();
    }

    private static OutboxEvent fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new OutboxEvent(
                row.getLong("seq"),
                row.getObject("id", UUID.class),
                row.getObject("payment_id", UUID.class),
                row.getString("type"),
                row.getString("payload"),
                row.getString("traceparent"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    // The PostgreSQL driver binds OffsetDateTime to timestamptz, but not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
