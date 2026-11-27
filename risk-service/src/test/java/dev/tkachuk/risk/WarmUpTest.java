package dev.tkachuk.risk;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.grpc.client.GrpcChannelFactory;

/** A warm-up that fails never stops risk-service from starting; RiskServiceIT shows one that works. */
class WarmUpTest {

    @Test
    void shouldLetRiskServiceStartWhenItCannotWarmUp() {
        GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
        when(channels.createChannel(WarmUp.CHANNEL)).thenThrow(new IllegalStateException("no channel"));
        WarmUp warmUp = new WarmUp(channels);

        assertThatCode(() -> warmUp.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    }
}
