package dev.tkachuk.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Logs the version at startup, what the ledger reads, and where traces go. */
@Component
class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    static final String TRACES_ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";

    private final ObjectProvider<BuildProperties> buildProperties;
    private final LedgerProperties properties;
    private final KafkaConnectionDetails kafka;
    private final Environment environment;

    StartupLogger(ObjectProvider<BuildProperties> buildProperties, LedgerProperties properties,
            KafkaConnectionDetails kafka, Environment environment) {
        this.buildProperties = buildProperties;
        this.properties = properties;
        this.kafka = kafka;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            log.warn("ledger-service started, version unknown: build-info.properties is missing (run ./mvnw package)");
        } else {
            log.info("ledger-service {} started", build.getVersion());
        }
        // The servers the consumer is built with. They are not always spring.kafka.bootstrap-servers: with
        // @ServiceConnection a test's container supplies them, and the property keeps its default.
        log.info("Reading {} from {}; events it can't read go to {}", properties.topic(),
                String.join(",", kafka.getConsumer().getBootstrapServers()), properties.deadLetterTopic());
        String traces = environment.getProperty(TRACES_ENDPOINT);
        if (traces == null || traces.isBlank()) {
            log.info("Traces are not exported: {} is not set", TRACES_ENDPOINT);
        } else {
            log.info("Traces go to {}", traces);
        }
    }
}
