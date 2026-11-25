package dev.tkachuk.gateway.merchant;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Stores the demo merchant at startup, or gives it the configured key if it exists already. */
@Component
class DemoMerchantSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoMerchantSetup.class);

    private final DemoMerchantProperties properties;
    private final MerchantRepository merchants;
    private final Clock clock;

    DemoMerchantSetup(DemoMerchantProperties properties, MerchantRepository merchants, Clock clock) {
        this.properties = properties;
        this.merchants = merchants;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("No demo merchant: gateway.demo-merchant.api-key is empty");
            return;
        }
        merchants.save(properties.code(), properties.name(), ApiKeys.hash(properties.apiKey()), clock.instant());
        log.info("Demo merchant {} is ready", properties.code());
    }
}
