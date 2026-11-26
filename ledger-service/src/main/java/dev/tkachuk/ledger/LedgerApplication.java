package dev.tkachuk.ledger;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * ledger-service: reads the gateway's payment-events topic and books each payment and refund that succeeded into a
 * double-entry ledger of its own, which an internal API shows. It shares no code with the gateway; the topic's
 * contract, docs/payment-events.yaml, is what both sides keep to.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
