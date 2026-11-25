package dev.tkachuk.acquirer;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * A stand-in for a card acquirer, so the gateway can be run and shown without a real one. It keeps its charges in
 * memory and decides by test card numbers; the API is its own, not the gateway's.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AcquirerSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcquirerSimApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
