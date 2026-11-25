package dev.tkachuk.gateway.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** WireMock in place of a merchant's webhook endpoint; the webhook tests point the demo merchant at it. */
@TestConfiguration(proxyBeanMethods = false)
public class MerchantEndpointStub {

    @Bean(destroyMethod = "stop")
    WireMockServer merchantEndpoint() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
}
