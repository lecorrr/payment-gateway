package dev.tkachuk.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoadSettingsTest {

    private static Map<String, String> env(String... namesAndValues) {
        Map<String, String> env = new HashMap<>(Map.of("GATEWAY_API_KEY", "sk_test_load"));
        for (int i = 0; i < namesAndValues.length; i += 2) {
            env.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return env;
    }

    @Test
    void shouldGoToStagingInSixDoublingStepsOfTwoMinutesByDefault() {
        LoadSettings settings = LoadSettings.from(env());

        assertThat(settings.gatewayUrl()).hasToString("http://gateway.staging.localhost:8280");
        assertThat(settings.ingressAddress()).isEqualTo("127.0.0.1");
        assertThat(settings.rates()).containsExactly(100, 200, 400, 800, 1600, 3200);
        assertThat(settings.stepDuration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.p99Budget()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void shouldTakeTheEnvironmentsValues() {
        LoadSettings settings = LoadSettings.from(env("GATEWAY_URL", "http://gateway.dev.localhost:8280",
                "INGRESS_ADDRESS", "docker", "LOAD_STEPS", " 10, 20 ,40", "LOAD_STEP_SECONDS", "30",
                "LOAD_P99_BUDGET_MS", "250"));

        assertThat(settings.gatewayUrl().getHost()).isEqualTo("gateway.dev.localhost");
        assertThat(settings.ingressAddress()).isEqualTo("docker");
        assertThat(settings.rates()).isEqualTo(List.of(10, 20, 40));
        assertThat(settings.stepDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.p99Budget()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void shouldNeedTheApiKey() {
        Map<String, String> withoutKey = env();
        withoutKey.remove("GATEWAY_API_KEY");

        assertThatIllegalArgumentException().isThrownBy(() -> LoadSettings.from(withoutKey))
                .withMessageContaining("GATEWAY_API_KEY");
        assertThatIllegalArgumentException().isThrownBy(() -> LoadSettings.from(env("GATEWAY_API_KEY", " ")))
                .withMessageContaining("GATEWAY_API_KEY");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "GATEWAY_URL        | gateway.staging.localhost:8280",
        "GATEWAY_URL        | ftp://gateway.staging.localhost",
        "GATEWAY_URL        | http://gateway.staging.localhost:8280/api/v1",
        "GATEWAY_URL        | http://gateway.staging.localhost:8280?x=1",
        "GATEWAY_URL        | http://gateway staging",
        "LOAD_STEPS         | 50,25",
        "LOAD_STEPS         | 25,25",
        "LOAD_STEPS         | 25,,50",
        "LOAD_STEPS         | 0,25",
        "LOAD_STEP_SECONDS  | 0",
        "LOAD_STEP_SECONDS  | -5",
        "LOAD_STEP_SECONDS  | 2m",
        "LOAD_P99_BUDGET_MS | 0.5",
    })
    void shouldRefuseAValueThatWouldSkewTheRun(String name, String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> LoadSettings.from(env(name, value)))
                .withMessageContaining(name);
    }

    @Test
    void shouldKeepItsOwnCopyOfTheRates() {
        List<Integer> rates = new ArrayList<>(List.of(25, 50));
        LoadSettings settings = new LoadSettings(LoadSettings.from(env()).gatewayUrl(), "127.0.0.1", "key", rates,
                Duration.ofSeconds(1), Duration.ofMillis(1));
        rates.add(100);

        assertThat(settings.rates()).containsExactly(25, 50);
    }
}
