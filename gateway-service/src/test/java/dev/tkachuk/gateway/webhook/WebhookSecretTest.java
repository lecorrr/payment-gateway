package dev.tkachuk.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookSecretTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    @Test
    void shouldUseDecodedBytesAsKey() {
        WebhookSecret secret = WebhookSecret.parse(SECRET);

        assertThat(secret.value()).isEqualTo(SECRET);
        assertThat(secret.key()).isEqualTo(Base64.getDecoder().decode("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")).hasSize(24);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", "whsk_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"})
    void shouldNeedPrefix(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> WebhookSecret.parse(value))
                .withMessageContaining("whsec_");
    }

    @Test
    void shouldNeedBase64WithoutQuotingIt() {
        assertThatIllegalArgumentException().isThrownBy(() -> WebhookSecret.parse("whsec_not*base64*at*all*here*01"))
                .withMessageContaining("Base64")
                .withMessageNotContaining("not*base64");
    }

    @Test
    void shouldNeedBetween24And64Bytes() {
        String tooShort = "whsec_" + Base64.getEncoder().encodeToString(new byte[23]);
        String tooLong = "whsec_" + Base64.getEncoder().encodeToString(new byte[65]);
        String longest = "whsec_" + Base64.getEncoder().encodeToString(new byte[64]);

        assertThatIllegalArgumentException().isThrownBy(() -> WebhookSecret.parse(tooShort))
                .withMessageContaining("23");
        assertThatIllegalArgumentException().isThrownBy(() -> WebhookSecret.parse(tooLong))
                .withMessageContaining("65");
        assertThat(WebhookSecret.parse(longest).key()).hasSize(64);
    }

    @Test
    void shouldHideItself() {
        assertThat(WebhookSecret.parse(SECRET).toString()).isEqualTo("whsec_***");
    }

    @Test
    void shouldGiveOutCopiesOfKey() {
        WebhookSecret secret = WebhookSecret.parse(SECRET);
        secret.key()[0] = 0;

        assertThat(secret.key()).isEqualTo(Base64.getDecoder().decode("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"));
    }
}
