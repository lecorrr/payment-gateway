package dev.tkachuk.gateway.provider.monobank;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class MonobankPublicKeyTest {

    private static final String PUBKEY = "/api/merchant/pubkey";
    private static final byte[] BODY = "{\"invoiceId\": \"p2_test_1\"}".getBytes(StandardCharsets.UTF_8);

    private static WireMockServer monobank;

    private final MonobankKeys first = MonobankKeys.generate();
    private final MonobankKeys second = MonobankKeys.generate();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-25T10:00:00Z"));

    private MonobankPublicKey publicKey;

    @BeforeAll
    static void startMonobank() {
        monobank = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        monobank.start();
    }

    @AfterAll
    static void stopMonobank() {
        monobank.stop();
    }

    @BeforeEach
    void resetMonobank() {
        monobank.resetAll();
        MonobankProperties properties = new MonobankProperties(monobank.baseUrl(), "test-monobank-token", "",
                Duration.ofMillis(300), Duration.ofMillis(700));
        publicKey = new MonobankPublicKey(new MonobankClient(RestClient.builder(), properties), clock);
    }

    @Test
    void shouldFetchKeyOnceAndKeepIt() {
        monobank.stubFor(get(PUBKEY).willReturn(key(first)));

        assertThat(publicKey.signed(BODY, first.sign(BODY))).isTrue();
        assertThat(publicKey.signed(BODY, first.sign(BODY))).isTrue();
        monobank.verify(1, getRequestedFor(urlEqualTo(PUBKEY)));
    }

    // Otherwise every forged webhook would have the gateway call monobank.
    @Test
    void shouldNotFetchKeyAgainForEveryMismatch() {
        monobank.stubFor(get(PUBKEY).willReturn(key(first)));

        assertThat(publicKey.signed(BODY, first.sign(BODY))).isTrue();
        clock.move(MonobankPublicKey.REFETCH_AFTER.minusSeconds(1));
        assertThat(publicKey.signed(BODY, second.sign(BODY))).isFalse();
        monobank.verify(1, getRequestedFor(urlEqualTo(PUBKEY)));
    }

    @Test
    void shouldFetchKeyAgainWhenMonobankMayHaveChangedIt() {
        monobank.stubFor(get(PUBKEY).willReturn(key(first)));
        assertThat(publicKey.signed(BODY, first.sign(BODY))).isTrue();

        monobank.stubFor(get(PUBKEY).willReturn(key(second)));
        clock.move(MonobankPublicKey.REFETCH_AFTER);

        assertThat(publicKey.signed(BODY, second.sign(BODY))).isTrue();
        monobank.verify(2, getRequestedFor(urlEqualTo(PUBKEY)));
    }

    @Test
    void shouldNotTellWhenKeyCannotBeFetched() {
        monobank.stubFor(get(PUBKEY).willReturn(aResponse().withStatus(403)));

        assertThatExceptionOfType(RestClientException.class).isThrownBy(() -> publicKey.signed(BODY, "c2lnbg=="));
    }

    @Test
    void shouldNotTellWhenAnswerHoldsNoKey() {
        monobank.stubFor(get(PUBKEY).willReturn(json("{}")));

        assertThatIllegalStateException().isThrownBy(() -> publicKey.signed(BODY, "c2lnbg=="))
                .withMessageContaining("without a key");
    }

    private static ResponseDefinitionBuilder key(MonobankKeys keys) {
        return json("{\"key\": \"" + keys.publicKeyBase64Pem() + "\"}");
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void move(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
