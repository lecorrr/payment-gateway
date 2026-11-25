package dev.tkachuk.gateway.webhook;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table webhook_endpoints: at most one endpoint per merchant. */
@Repository
public class WebhookEndpoints {

    private final JdbcClient jdbc;

    WebhookEndpoints(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Gives the merchant this endpoint, in place of the one it had. */
    public void save(UUID merchantId, URI url, WebhookSecret secret, Instant now) {
        jdbc.sql("""
                        INSERT INTO webhook_endpoints (merchant_id, url, secret, created_at, updated_at)
                        VALUES (:merchantId, :url, :secret, :now, :now)
                        ON CONFLICT (merchant_id) DO UPDATE
                        SET url = EXCLUDED.url, secret = EXCLUDED.secret, updated_at = EXCLUDED.updated_at""")
                .param("merchantId", merchantId)
                .param("url", url.toString())
                .param("secret", secret.value())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** The merchant gets no more webhooks; events not sent yet are given up when their turn comes. */
    public void delete(UUID merchantId) {
        jdbc.sql("DELETE FROM webhook_endpoints WHERE merchant_id = :merchantId")
                .param("merchantId", merchantId)
                .update();
    }

    Optional<WebhookEndpoint> find(UUID merchantId) {
        return jdbc.sql("SELECT merchant_id, url, secret FROM webhook_endpoints WHERE merchant_id = :merchantId")
                .param("merchantId", merchantId)
                .query(WebhookEndpoints::fromRow)
                .optional();
    }

    private static WebhookEndpoint fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new WebhookEndpoint(row.getObject("merchant_id", UUID.class), URI.create(row.getString("url")),
                WebhookSecret.parse(row.getString("secret")));
    }
}
