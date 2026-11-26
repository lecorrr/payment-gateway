package dev.tkachuk.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the version and the rules at startup. */
@Component
class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final ObjectProvider<BuildProperties> buildProperties;
    private final RiskProperties properties;

    StartupLogger(ObjectProvider<BuildProperties> buildProperties, RiskProperties properties) {
        this.buildProperties = buildProperties;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logStart() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            log.warn("risk-service started, version unknown: build-info.properties is missing (run ./mvnw package)");
        } else {
            log.info("risk-service {} started", build.getVersion());
        }
        log.info("Payments declined: more than {} with one card in {}, over the limits {}, from card ranges {}",
                properties.velocity().maxPayments(), properties.velocity().window(), properties.amountLimits(),
                properties.blockedBins());
    }
}
