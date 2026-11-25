package dev.tkachuk.gateway.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** gateway.providers: which provider new payments are charged with. Each provider has its own settings below it. */
@ConfigurationProperties("gateway.providers")
public record ProvidersProperties(String active) {

    public ProvidersProperties {
        if (active == null || active.isBlank()) {
            throw new IllegalArgumentException("gateway.providers.active is empty; set GATEWAY_PROVIDER");
        }
    }
}
