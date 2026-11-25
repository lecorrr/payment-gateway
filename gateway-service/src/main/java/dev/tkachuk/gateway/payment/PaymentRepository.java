package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.CardBrand;
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
 * Table payments. Every statement commits on its own; an outcome is only written while the payment is still
 * PROCESSING, so the request and the checker can't overwrite each other, and the first outcome stands.
 */
@Repository
class PaymentRepository {

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False if the merchant has a payment with this Idempotency-Key already. */
    boolean insertIfAbsent(Payment payment) {
        int inserted = jdbc.sql("""
                        INSERT INTO payments (id, merchant_id, idempotency_key, request_fingerprint, order_id, amount,
                            currency, description, status, card_brand, card_bin, card_last4, provider, next_check_at,
                            created_at, updated_at)
                        VALUES (:id, :merchantId, :idempotencyKey, :requestFingerprint, :orderId, :amount,
                            :currency, :description, :status, :cardBrand, :cardBin, :cardLast4, :provider, :nextCheckAt,
                            :createdAt, :updatedAt)
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING""")
                .param("id", payment.id())
                .param("merchantId", payment.merchantId())
                .param("idempotencyKey", payment.idempotencyKey())
                .param("requestFingerprint", payment.requestFingerprint())
                .param("orderId", payment.orderId())
                .param("amount", payment.amount())
                .param("currency", payment.currency().name())
                .param("description", payment.description())
                .param("status", payment.status().name())
                .param("cardBrand", payment.cardBrand().name())
                .param("cardBin", payment.cardBin())
                .param("cardLast4", payment.cardLast4())
                .param("provider", payment.provider())
                .param("nextCheckAt", utc(payment.nextCheckAt()))
                .param("createdAt", utc(payment.createdAt()))
                .param("updatedAt", utc(payment.updatedAt()))
                .update();
        return inserted == 1;
    }

    /** Only the merchant's own payments: for any other id the result is empty. */
    Optional<Payment> find(UUID merchantId, UUID id) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id AND merchant_id = :merchantId")
                .param("id", id)
                .param("merchantId", merchantId)
                .query(PaymentRepository::fromRow)
                .optional();
    }

    Optional<Payment> findByIdempotencyKey(UUID merchantId, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM payments WHERE merchant_id = :merchantId AND idempotency_key = :idempotencyKey")
                .param("merchantId", merchantId)
                .param("idempotencyKey", idempotencyKey)
                .query(PaymentRepository::fromRow)
                .optional();
    }

    /** Records the outcome. Empty if the payment had one already, which then stands. */
    Optional<Payment> finish(UUID id, PaymentStatus status, @Nullable String providerReference,
            @Nullable String declineCode, Instant now) {
        if (!status.isFinal()) {
            throw new IllegalArgumentException(status + " is not an outcome");
        }
        return jdbc.sql("""
                        UPDATE payments
                        SET status = :status, provider_reference = :providerReference, decline_code = :declineCode,
                            next_check_at = NULL, updated_at = :now
                        WHERE id = :id AND status = 'PROCESSING'
                        RETURNING *""")
                .param("id", id)
                .param("status", status.name())
                .param("providerReference", providerReference)
                .param("declineCode", declineCode)
                .param("now", utc(now))
                .query(PaymentRepository::fromRow)
                .optional();
    }

    /**
     * Claims PROCESSING payments due for a check by moving their next check to leaseUntil, so a checker running in
     * another instance skips them. SKIP LOCKED lets instances claim side by side without waiting for each other.
     */
    List<Payment> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE payments SET next_check_at = :leaseUntil
                        WHERE id IN (
                            SELECT id FROM payments
                            WHERE status = 'PROCESSING' AND next_check_at <= :now
                            ORDER BY next_check_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *""")
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(PaymentRepository::fromRow)
                .list();
    }

    private static Payment fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new Payment(
                row.getObject("id", UUID.class),
                row.getObject("merchant_id", UUID.class),
                row.getString("idempotency_key"),
                row.getString("request_fingerprint"),
                row.getString("order_id"),
                row.getLong("amount"),
                Currency.valueOf(row.getString("currency")),
                row.getString("description"),
                PaymentStatus.valueOf(row.getString("status")),
                CardBrand.valueOf(row.getString("card_brand")),
                row.getString("card_bin"),
                row.getString("card_last4"),
                row.getString("provider"),
                row.getString("provider_reference"),
                row.getString("decline_code"),
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
