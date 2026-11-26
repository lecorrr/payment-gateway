package dev.tkachuk.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/** One Kafka broker in KRaft mode, the image compose and the chart run, for the payment-events topic. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainer {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(TestImages.fromSystemProperty("kafka.image"));
    }
}
