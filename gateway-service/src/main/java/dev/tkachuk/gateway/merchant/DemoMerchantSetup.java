package dev.tkachuk.gateway.merchant;

import dev.tkachuk.gateway.webhook.WebhookEndpoints;
import dev.tkachuk.gateway.webhook.WebhookSecret;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Stores the demo merchant at startup, or gives it the configured key if it exists already, and gives it the
 * configured webhook endpoint, or takes its endpoint away when none is configured.
 */
@Component
class DemoMerchantSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoMerchantSetup.class);

    private final DemoMerchantProperties properties;
    private final MerchantRepository merchants;
    private final WebhookEndpoints webhookEndpoints;
    private final Clock clock;

    DemoMerchantSetup(DemoMerchantProperties properties, MerchantRepository merchants,
            WebhookEndpoints webhookEndpoints, Clock clock) {
        this.properties = properties;
        this.merchants = merchants;
        this.webhookEndpoints = webhookEndpoints;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("No demo merchant: gateway.demo-merchant.api-key is empty");
            return;
        }
        Instant now = clock.instant();
        UUID merchantId = merchants.save(properties.code(), properties.name(), ApiKeys.hash(properties.apiKey()), now);
        if (properties.hasWebhook()) {
            webhookEndpoints.save(merchantId, URI.create(properties.webhookUrl()),
                    WebhookSecret.parse(properties.webhookSecret()), now);
            log.info("Demo merchant {} is ready, with webhooks to {}", properties.code(), properties.webhookUrl());
        } else {
            webhookEndpoints.delete(merchantId);
            log.info("Demo merchant {} is ready, without webhooks", properties.code());
        }
    }
}
