package dev.tkachuk.risk;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * risk-service: tells the gateway, over gRPC (risk.proto in risk-api), whether a card payment may go ahead. It keeps
 * no payments, only each card's recent attempts in Redis.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RiskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
