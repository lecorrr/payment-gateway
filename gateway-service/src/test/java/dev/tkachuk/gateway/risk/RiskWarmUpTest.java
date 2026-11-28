package dev.tkachuk.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.tkachuk.gateway.risk.RiskProperties.WhenUnavailable;
import dev.tkachuk.gateway.support.FakeRiskService;
import io.grpc.BindableService;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** The warm-up against a gRPC server in-process: one with the health service, one without, none, and a late one. */
class RiskWarmUpTest {

    private static final RiskProperties CHECKS_ON =
            new RiskProperties(true, Duration.ofMillis(300), WhenUnavailable.ALLOW, Duration.ofSeconds(30));
    private static final RiskProperties CHECKS_OFF =
            new RiskProperties(false, Duration.ofMillis(300), WhenUnavailable.ALLOW, Duration.ofSeconds(30));
    private static final Duration PER_ATTEMPT = Duration.ofMillis(100);

    private final String name = InProcessServerBuilder.generateName();
    private final ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
    private @Nullable Server server;

    @AfterEach
    void stop() {
        channel.shutdownNow();
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void shouldConnectToRiskServiceThatAnswersItsHealth() throws IOException {
        startServer(new HealthStatusManager().getHealthService());

        assertThat(new RiskWarmUp(channel, CHECKS_ON).warmUp(10, PER_ATTEMPT)).isTrue();
        assertThat(channel.getState(false)).isEqualTo(ConnectivityState.READY);
    }

    // It answers UNIMPLEMENTED, over a channel as connected as after a health check.
    @Test
    void shouldTakeAnyAnswerOfRiskServiceWithoutTheHealthService() throws IOException {
        startServer(new FakeRiskService());

        assertThat(new RiskWarmUp(channel, CHECKS_ON).warmUp(10, PER_ATTEMPT)).isTrue();
        assertThat(channel.getState(false)).isEqualTo(ConnectivityState.READY);
    }

    // Every pod started at once, and the gateway was ready first.
    @Test
    void shouldConnectToRiskServiceThatStartsAfterTheGateway() throws Exception {
        CompletableFuture<Boolean> warmedUp = CompletableFuture.supplyAsync(
                () -> new RiskWarmUp(channel, CHECKS_ON).warmUp(50, PER_ATTEMPT));
        Thread.sleep(300);
        startServer(new HealthStatusManager().getHealthService());

        assertThat(warmedUp.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldGiveUpOnRiskServiceThatNeverAnswers() {
        assertThat(new RiskWarmUp(channel, CHECKS_ON).warmUp(3, PER_ATTEMPT)).isFalse();
    }

    @Test
    void shouldLeaveTheChannelAloneWhileRiskChecksAreOff() {
        new RiskWarmUp(channel, CHECKS_OFF).run(mock(ApplicationArguments.class));

        assertThat(channel.getState(false)).isEqualTo(ConnectivityState.IDLE);
    }

    private void startServer(BindableService service) throws IOException {
        server = InProcessServerBuilder.forName(name).addService(service).build().start();
    }
}
