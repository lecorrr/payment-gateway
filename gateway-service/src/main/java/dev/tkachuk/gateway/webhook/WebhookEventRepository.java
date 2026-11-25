package dev.tkachuk.gateway.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Table webhook_events, the outbox. An event is PENDING until it is DELIVERED or FAILED (given up); the updates
 * only touch PENDING events, so a late answer can't undo a decision already made.
 */
@Repository
class WebhookEventRepository {

    private final JdbcClient jdbc;

    WebhookEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds the event, due right away, if the merchant has a webhook endpoint; returns whether it did. */
    boolean insertIfEndpoint(WebhookEvent event) {
        int inserted = jdbc.sql("""
                        INSERT INTO webhook_events (id, merchant_id, payment_id, type, payload, status, attempts,
                            next_attempt_at, created_at)
                        SELECT :id, :merchantId, :paymentId, :type, :payload, 'PENDING', 0, :createdAt, :createdAt
                        WHERE EXISTS (SELECT 1 FROM webhook_endpoints WHERE merchant_id = :merchantId)""")
                .param("id", event.id())
                .param("merchantId", event.merchantId())
                .param("paymentId", event.paymentId())
                .param("type", event.type())
                .param("payload", event.payload())
                .param("createdAt", utc(event.createdAt()))
                .update();
        return inserted == 1;
    }

    /**
     * Claims the events due for an attempt by moving their next attempt to leaseUntil, so a dispatcher in another
     * instance skips them; see PaymentRepository.claimDue.
     */
    List<WebhookEvent> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE webhook_events SET next_attempt_at = :leaseUntil
                        WHERE id IN (
                            SELECT id FROM webhook_events
                            WHERE next_attempt_at <= :now
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id, merchant_id, payment_id, type, payload, attempts, created_at""")
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(WebhookEventRepository::fromRow)
                .list();
    }

    void markDelivered(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE webhook_events
                        SET status = 'DELIVERED', attempts = attempts + 1, next_attempt_at = NULL, last_error = NULL,
                            delivered_at = :now
                        WHERE id = :id AND status = 'PENDING'""")
                .param("id", id)
                .param("now", utc(now))
                .update();
    }

    void scheduleRetry(UUID id, String error, Instant nextAttemptAt) {
        jdbc.sql("""
                        UPDATE webhook_events
                        SET attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAttemptAt
                        WHERE id = :id AND status = 'PENDING'""")
                .param("id", id)
                .param("error", error)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .update();
    }

    /** Gives the event up. attempted: whether this decision follows an attempt, which then counts. */
    void markFailed(UUID id, String error, boolean attempted) {
        jdbc.sql("""
                        UPDATE webhook_events
                        SET status = 'FAILED', attempts = attempts + :attempted, last_error = :error,
                            next_attempt_at = NULL
                        WHERE id = :id AND status = 'PENDING'""")
                .param("id", id)
                .param("attempted", attempted ? 1 : 0)
                .param("error", error)
                .update();
    }

    private static WebhookEvent fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new WebhookEvent(
                row.getObject("id", UUID.class),
                row.getObject("merchant_id", UUID.class),
                row.getObject("payment_id", UUID.class),
                row.getString("type"),
                row.getString("payload"),
                row.getInt("attempts"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    // The PostgreSQL driver binds OffsetDateTime to timestamptz, but not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
