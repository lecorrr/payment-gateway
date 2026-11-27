package dev.tkachuk.loadtest;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a run does, from environment variables, which deploy/k3d/load-test.sh sets: where the gateway is, the demo
 * merchant's API key, the rates and how long each lasts, and the p99 budget the knee is found by. Each is checked
 * before a payment is sent, so a wrong value stops the run with its name instead of skewing it.
 */
record LoadSettings(URI gatewayUrl, String ingressAddress, String apiKey, List<Integer> rates, Duration stepDuration,
        Duration p99Budget) {

    static final String DEFAULT_GATEWAY_URL = "http://gateway.staging.localhost:8280";
    static final String DEFAULT_RATES = "100,200,400,800,1600,3200";

    LoadSettings {
        rates = List.copyOf(rates);
    }

    static LoadSettings from(Map<String, String> env) {
        return new LoadSettings(
                gatewayUrl(env.getOrDefault("GATEWAY_URL", DEFAULT_GATEWAY_URL)),
                env.getOrDefault("INGRESS_ADDRESS", "127.0.0.1"),
                apiKey(env.get("GATEWAY_API_KEY")),
                rates(env.getOrDefault("LOAD_STEPS", DEFAULT_RATES)),
                Duration.ofSeconds(positive("LOAD_STEP_SECONDS", env.getOrDefault("LOAD_STEP_SECONDS", "120"))),
                Duration.ofMillis(positive("LOAD_P99_BUDGET_MS", env.getOrDefault("LOAD_P99_BUDGET_MS", "500"))));
    }

    private static URI gatewayUrl(String value) {
        URI url;
        try {
            url = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("GATEWAY_URL is no URL: " + value, e);
        }
        boolean http = "http".equals(url.getScheme()) || "https".equals(url.getScheme());
        boolean bare = url.getPath() == null || url.getPath().isEmpty() || "/".equals(url.getPath());
        if (!http || url.getHost() == null || !bare || url.getQuery() != null) {
            throw new IllegalArgumentException(
                    "GATEWAY_URL must be the gateway's address, such as " + DEFAULT_GATEWAY_URL + ", not " + value);
        }
        return url;
    }

    private static String apiKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GATEWAY_API_KEY is needed: the demo merchant's API key, which "
                    + "deploy/k3d/load-test.sh reads from the environment's Secret");
        }
        return value;
    }

    // Rising rates, as the knee is the first one the gateway can't keep up with.
    private static List<Integer> rates(String value) {
        List<Integer> rates = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            int rate = positive("LOAD_STEPS", part.strip());
            if (!rates.isEmpty() && rate <= rates.getLast()) {
                throw new IllegalArgumentException("LOAD_STEPS must rise, such as " + DEFAULT_RATES + ", not " + value);
            }
            rates.add(rate);
        }
        return rates;
    }

    private static int positive(String name, String value) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, not \"" + value + "\"", e);
        }
        if (number <= 0) {
            throw new IllegalArgumentException(name + " must be above 0, not " + number);
        }
        return number;
    }
}
