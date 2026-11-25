package dev.tkachuk.merchant;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * A stand-in for a shop's backend, so the gateway's webhooks and the 3-D Secure return can be shown end to end. It
 * checks every webhook's signature the way a real merchant should, and keeps the latest ones in memory.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MerchantSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantSimApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
