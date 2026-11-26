package dev.tkachuk.ledger.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The ledger's PostgreSQL and a Kafka broker in KRaft mode, the images compose and the chart run, and the
 * payment-events topic with the three partitions the gateway makes it with, made at startup with the dead-letter
 * topic, before the listener subscribes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LedgerContainers {

    public static final String TOPIC = "payment-events";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(TestImages.fromSystemProperty("postgres.image"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(TestImages.fromSystemProperty("kafka.image"));
    }

    @Bean
    NewTopic paymentEvents() {
        return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
    }
}
