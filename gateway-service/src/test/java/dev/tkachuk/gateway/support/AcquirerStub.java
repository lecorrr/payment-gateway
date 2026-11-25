package dev.tkachuk.gateway.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** WireMock in place of acquirer-sim; each test stubs the answers it needs. */
@TestConfiguration(proxyBeanMethods = false)
public class AcquirerStub {

    @Bean(destroyMethod = "stop")
    WireMockServer acquirer() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar acquirerUrl(WireMockServer acquirer) {
        return registry -> registry.add("gateway.providers.acquirer-sim.base-url", acquirer::baseUrl);
    }
}
