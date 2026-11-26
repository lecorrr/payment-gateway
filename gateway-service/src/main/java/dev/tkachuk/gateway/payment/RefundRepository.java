package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.common.Currency;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Table refunds. A refund's outcome is only written while it is PROCESSING, so the request, the checker and a
 * provider's webhook can't overwrite each other, and the first outcome stands.
 */
@Repository
class RefundRepository {

    private final JdbcClient jdbc;

    RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False if the merchant has a refund with this Idempotency-Key already. */
    boolean insertIfAbsent(Refund refund) {
        int inserted = jdbc.sql("""
                        INSERT INTO refunds (id, payment_id, merchant_id, idempotency_key, request_fingerprint, amount,
                            currency, status, provider, next_check_at, created_at, updated_at)
                        VALUES (:id, :paymentId, :merchantId, :idempotencyKey, :requestFingerprint, :amount,
                            :currency, :status, :provider, :nextCheckAt, :createdAt, :updatedAt)
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING""")
                .param("id", refund.id())
                .param("paymentId", refund.paymentId())
                .param("merchantId", refund.merchantId())
                .param("idempotencyKey", refund.idempotencyKey())
                .param("requestFingerprint", refund.requestFingerprint())
                .param("amount", refund.amount())
                .param("currency", refund.currency().name())
                .param("status", refund.status().name())
                .param("provider", refund.provider())
                .param("nextCheckAt", utc(refund.nextCheckAt()))
                .param("createdAt", utc(refund.createdAt()))
                .param("updatedAt", utc(refund.updatedAt()))
                .update();
        return inserted == 1;
    }

    /** Only the merchant's own refund of this payment: for any other id the result is empty. */
    Optional<Refund> find(UUID merchantId, UUID paymentId, UUID id) {
        return jdbc.sql("""
                        SELECT * FROM refunds
                        WHERE id = :id AND payment_id = :paymentId AND merchant_id = :merchantId""")
                .param("id", id)
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .query(RefundRepository::fromRow)
                .optional();
    }

    Optional<Refund> findById(UUID id) {
        return jdbc.sql("SELECT * FROM refunds WHERE id = :id")
                .param("id", id)
                .query(RefundRepository::fromRow)
                .optional();
    }

    Optional<Refund> findByIdempotencyKey(UUID merchantId, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM refunds WHERE merchant_id = :merchantId AND idempotency_key = :idempotencyKey")
                .param("merchantId", merchantId)
                .param("idempotencyKey", idempotencyKey)
                .query(RefundRepository::fromRow)
                .optional();
    }

    /** The payment's refunds, the newest first. */
    List<Refund> ofPayment(UUID merchantId, UUID paymentId) {
        return jdbc.sql("""
                        SELECT * FROM refunds
                        WHERE payment_id = :paymentId AND merchant_id = :merchantId
                        ORDER BY created_at DESC, id""")
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .query(RefundRepository::fromRow)
                .list();
    }

    /** The payment's refunds that have no outcome yet. */
    List<Refund> processingOfPayment(UUID paymentId) {
        return jdbc.sql("""
                        SELECT * FROM refunds
                        WHERE payment_id = :paymentId AND status = 'PROCESSING'
                        ORDER BY created_at""")
                .param("paymentId", paymentId)
                .query(RefundRepository::fromRow)
                .list();
    }

    /**
     * What the payment's refunds have taken already: the SUCCEEDED ones, and the PROCESSING ones, which may still
     * succeed. Read under the payment's lock (PaymentRepository.lockForRefund), it can't change before the
     * transaction ends.
     */
    long reservedAmount(UUID paymentId) {
        Long reserved = jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0)::bigint FROM refunds
                        WHERE payment_id = :paymentId AND status IN ('PROCESSING', 'SUCCEEDED')""")
                .param("paymentId", paymentId)
                .query(Long.class)
                .single();
        return reserved == null ? 0 : reserved;
    }

    /**
     * Records the outcome; a provider reference the refund has already stays unless a new one comes. Empty if the
     * refund had an outcome already, which then stands.
     */
    Optional<Refund> finish(UUID id, RefundStatus status, @Nullable String providerReference,
            @Nullable String failureCode, Instant now) {
        if (!status.isFinal()) {
            throw new IllegalArgumentException(status + " is not an outcome");
        }
        return jdbc.sql("""
                        UPDATE refunds
                        SET status = :status, provider_reference = COALESCE(:providerReference, provider_reference),
                            failure_code = :failureCode, next_check_at = NULL, updated_at = :now
                        WHERE id = :id AND status = 'PROCESSING'
                        RETURNING *""")
                .param("id", id)
                .param("status", status.name())
                .param("providerReference", providerReference)
                .param("failureCode", failureCode)
                .param("now", utc(now))
                .query(RefundRepository::fromRow)
                .optional();
    }

    /**
     * Keeps the provider's id for a refund that has no outcome yet. Empty when the refund has an id already or is
     * finished: then there is nothing to keep.
     */
    Optional<Refund> rememberProviderReference(UUID id, String providerReference, Instant now) {
        return jdbc.sql("""
                        UPDATE refunds
                        SET provider_reference = :providerReference, updated_at = :now
                        WHERE id = :id AND provider_reference IS NULL AND status = 'PROCESSING'
                        RETURNING *""")
                .param("id", id)
                .param("providerReference", providerReference)
                .param("now", utc(now))
                .query(RefundRepository::fromRow)
                .optional();
    }

    /** Claims the refunds due for a check, as PaymentRepository.claimDue claims payments. */
    List<Refund> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE refunds SET next_check_at = :leaseUntil
                        WHERE id IN (
                            SELECT id FROM refunds
                            WHERE next_check_at <= :now
                            ORDER BY next_check_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *""")
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(RefundRepository::fromRow)
                .list();
    }

    private static Refund fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new Refund(
                row.getObject("id", UUID.class),
                row.getObject("payment_id", UUID.class),
                row.getObject("merchant_id", UUID.class),
                row.getString("idempotency_key"),
                row.getString("request_fingerprint"),
                row.getLong("amount"),
                Currency.valueOf(row.getString("currency")),
                RefundStatus.valueOf(row.getString("status")),
                row.getString("provider"),
                row.getString("provider_reference"),
                row.getString("failure_code"),
                instantOf(row, "next_check_at"),
                instantOf(row, "created_at"),
                instantOf(row, "updated_at"));
    }

    private static @Nullable Instant instantOf(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // The PostgreSQL driver binds OffsetDateTime to timestamptz, but not Instant.
    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
