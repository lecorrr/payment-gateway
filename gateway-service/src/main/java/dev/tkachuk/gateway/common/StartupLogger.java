package dev.tkachuk.gateway.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Logs the version at startup, and where traces go. */
@Component
class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    static final String TRACES_ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";

    private final ObjectProvider<BuildProperties> buildProperties;
    private final Environment environment;

    StartupLogger(ObjectProvider<BuildProperties> buildProperties, Environment environment) {
        this.buildProperties = buildProperties;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            log.warn("gateway-service started, version unknown: build-info.properties is missing (run ./mvnw package)");
        } else {
            log.info("gateway-service {} started", build.getVersion());
        }
        String traces = environment.getProperty(TRACES_ENDPOINT);
        if (traces == null || traces.isBlank()) {
            log.info("Traces are not exported: {} is not set", TRACES_ENDPOINT);
        } else {
            log.info("Traces go to {}", traces);
        }
    }
}
