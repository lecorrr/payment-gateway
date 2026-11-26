package dev.tkachuk.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.gateway.support.IntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migrations of this version against a database of the version before, with its data in it, as an upgrade
 * meets it: Flyway takes the database from there, and what was in it stays. Runs in a database of its own on the
 * tests' PostgreSQL, so the shared one keeps its schema.
 */
class MigrationIT extends IntegrationTest {

    // The last migration of the version before this one, 0.8.0; it moves on with each release that adds one.
    private static final String PREVIOUS_VERSION_SCHEMA = "3";

    @Autowired
    private PostgreSQLContainer postgres;

    private final String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate upgraded;

    @BeforeEach
    void makeDatabase() {
        jdbc.execute("CREATE DATABASE " + database);
        upgraded = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void dropDatabase() {
        jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void shouldUpgradeDatabaseOfPreviousVersionKeepingItsData() {
        migrations().target(PREVIOUS_VERSION_SCHEMA).load().migrate();
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        upgraded.update("""
                INSERT INTO merchants (id, code, name, api_key_hash, created_at)
                VALUES (?, 'old-shop', 'Old Shop', repeat('a', 64), now())""", merchantId);
        upgraded.update("""
                INSERT INTO payments (id, merchant_id, idempotency_key, request_fingerprint, order_id, amount, currency,
                    status, card_brand, card_bin, card_last4, provider, provider_reference, created_at, updated_at,
                    refunded_amount)
                VALUES (?, ?, 'pay-1', repeat('b', 64), 'ORDER-1', 15000, 'UAH', 'SUCCEEDED', 'VISA', '411111', '1111',
                    'acquirer-sim', 'ch_1', now(), now(), 5000)""", paymentId, merchantId);
        upgraded.update("""
                INSERT INTO refunds (id, payment_id, merchant_id, idempotency_key, request_fingerprint, amount, currency,
                    status, provider, created_at, updated_at)
                VALUES (?, ?, ?, 'refund-1', repeat('c', 64), 5000, 'UAH', 'SUCCEEDED', 'acquirer-sim', now(), now())""",
                UUID.randomUUID(), paymentId, merchantId);

        migrations().load().migrate();

        assertThat(upgraded.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", Long.class, paymentId))
                .isEqualTo(5000);
        assertThat(upgraded.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Integer.class,
                paymentId)).isEqualTo(1);
        // A payment from before the upgrade can have events, as when its refund comes later.
        upgraded.update("""
                INSERT INTO event_outbox (id, payment_id, type, payload, created_at)
                VALUES (?, ?, 'refund.succeeded', '{}', now())""", UUID.randomUUID(), paymentId);
        assertThat(upgraded.queryForObject("SELECT count(*) FROM event_outbox WHERE published_at IS NULL",
                Integer.class)).isEqualTo(1);
    }

    private FluentConfiguration migrations() {
        return Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration");
    }

    private DataSource dataSource() {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }
}
