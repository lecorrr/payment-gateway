package dev.tkachuk.merchant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookVerifierTest {

    // The example the Standard Webhooks libraries are tested with; the gateway's signing test uses the same one.
    static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final String TIMESTAMP = "1614265330";
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";
    private static final Instant SENT_AT = Instant.ofEpochSecond(1614265330L);

    private final WebhookVerifier verifier = new WebhookVerifier(SECRET, Duration.ofMinutes(5));

    @Test
    void shouldTrustWebhookSignedWithSecret() {
        assertThatCode(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY, SENT_AT.plusSeconds(30)))
                .doesNotThrowAnyException();
    }

    // While the sender rotates its secret it signs with both; one match is enough.
    @Test
    void shouldTrustWebhookWithOneMatchingSignatureOfSeveral() {
        String signatures = "v1,bm90IHRoaXMgb25l " + SIGNATURE + " v2a,c29tZXRoaW5nIGVsc2U=";

        assertThatCode(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, signatures, BODY, SENT_AT))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseChangedBodyIdOrTimestamp() {
        byte[] changedBody = "{\"test\": 2432232315}".getBytes(StandardCharsets.UTF_8);

        assertRefused(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, SIGNATURE, changedBody, SENT_AT), "no signature");
        assertRefused(() -> verifier.verify("msg_other", TIMESTAMP, SIGNATURE, BODY, SENT_AT), "no signature");
        assertRefused(() -> verifier.verify(MESSAGE_ID, "1614265331", SIGNATURE, BODY, SENT_AT), "no signature");
    }

    @ParameterizedTest
    @ValueSource(strings = {"g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=", "v1,not*base64", "v1,", ""})
    void shouldRefuseSignatureInOtherForm(String signatures) {
        assertRefused(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, signatures, BODY, SENT_AT), "no signature");
    }

    @Test
    void shouldRefuseWebhookOutsideTolerance() {
        assertRefused(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY, SENT_AT.plusSeconds(301)),
                "from now");
        assertRefused(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, SIGNATURE, BODY, SENT_AT.minusSeconds(301)),
                "from now");
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "1614265330.5", "99999999999999999999"})
    void shouldRefuseTimestampThatIsNoUnixTime(String timestamp) {
        assertRefused(() -> verifier.verify(MESSAGE_ID, timestamp, SIGNATURE, BODY, SENT_AT), "Unix seconds");
    }

    @Test
    void shouldNeedAllThreeHeaders() {
        assertRefused(() -> verifier.verify(null, TIMESTAMP, SIGNATURE, BODY, SENT_AT), "needed");
        assertRefused(() -> verifier.verify(" ", TIMESTAMP, SIGNATURE, BODY, SENT_AT), "needed");
        assertRefused(() -> verifier.verify(MESSAGE_ID, null, SIGNATURE, BODY, SENT_AT), "needed");
        assertRefused(() -> verifier.verify(MESSAGE_ID, TIMESTAMP, null, BODY, SENT_AT), "needed");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", "whsec_not*base64*at*all*here*01"})
    void shouldRefuseSecretOfWrongFormat(String secret) {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebhookVerifier(secret, Duration.ofMinutes(5)))
                .withMessageContaining("whsec_");
    }

    @Test
    void shouldRefuseSecretOfWrongSize() {
        String tooShort = "whsec_" + Base64.getEncoder().encodeToString(new byte[23]);

        assertThatIllegalArgumentException().isThrownBy(() -> new WebhookVerifier(tooShort, Duration.ofMinutes(5)))
                .withMessageContaining("24 to 64");
    }

    private static void assertRefused(Runnable verification, String reason) {
        assertThatThrownBy(verification::run).isInstanceOf(InvalidWebhookException.class).hasMessageContaining(reason);
    }
}
