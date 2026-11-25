package dev.tkachuk.gateway.merchant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table merchants. */
@Repository
public class MerchantRepository {

    private final JdbcClient jdbc;

    MerchantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Merchant> findByApiKeyHash(String apiKeyHash) {
        return jdbc.sql("SELECT id, code, name FROM merchants WHERE api_key_hash = :apiKeyHash")
                .param("apiKeyHash", apiKeyHash)
                .query(MerchantRepository::fromRow)
                .optional();
    }

    /** Adds the merchant, or gives the one with this code the new name and key; returns the merchant's id. */
    public UUID save(String code, String name, String apiKeyHash, Instant now) {
        return jdbc.sql("""
                        INSERT INTO merchants (id, code, name, api_key_hash, created_at)
                        VALUES (:id, :code, :name, :apiKeyHash, :now)
                        ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, api_key_hash = EXCLUDED.api_key_hash
                        RETURNING id""")
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("name", name)
                .param("apiKeyHash", apiKeyHash)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((row, rowNumber) -> row.getObject("id", UUID.class))
                .single();
    }

    private static Merchant fromRow(ResultSet row, int rowNumber) throws SQLException {
        return new Merchant(row.getObject("id", UUID.class), row.getString("code"), row.getString("name"));
    }
}
