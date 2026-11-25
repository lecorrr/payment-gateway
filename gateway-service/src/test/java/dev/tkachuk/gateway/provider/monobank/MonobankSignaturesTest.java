package dev.tkachuk.gateway.provider.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonobankSignaturesTest {

    private static final MonobankKeys KEYS = MonobankKeys.generate();
    private static final byte[] BODY = """
            {"invoiceId": "p2_test_1", "status": "success", "amount": 15000, "ccy": 980,
             "reference": "7f1c2b3a-0000-4000-8000-000000000001"}""".getBytes(StandardCharsets.UTF_8);

    private final PublicKey key = MonobankSignatures.parsePublicKey(KEYS.publicKeyBase64Pem());

    @Test
    void shouldAcceptSignatureOverExactBody() {
        assertThat(MonobankSignatures.isValid(key, BODY, KEYS.sign(BODY))).isTrue();
    }

    @Test
    void shouldRefuseSignatureOfAnotherBody() {
        String signature = KEYS.sign(BODY);
        byte[] changed = new String(BODY, StandardCharsets.UTF_8).replace("success", "failure")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(MonobankSignatures.isValid(key, changed, signature)).isFalse();
    }

    @Test
    void shouldRefuseSignatureOfAnotherKey() {
        assertThat(MonobankSignatures.isValid(key, BODY, MonobankKeys.generate().sign(BODY))).isFalse();
    }

    // Not Base64, and Base64 of something that is no DER signature.
    @ParameterizedTest
    @ValueSource(strings = {"not*base64", "", "bm90IGEgc2lnbmF0dXJl"})
    void shouldRefuseSignatureThatIsNoSignature(String signature) {
        assertThat(MonobankSignatures.isValid(key, BODY, signature)).isFalse();
    }

    @Test
    void shouldReadKeyWhoseBase64IsWrappedOverLines() {
        String wrapped = KEYS.publicKeyBase64Pem().replaceAll("(.{60})", "$1\n");

        PublicKey parsed = MonobankSignatures.parsePublicKey(wrapped);

        assertThat(MonobankSignatures.isValid(parsed, BODY, KEYS.sign(BODY))).isTrue();
    }

    @Test
    void shouldRefuseKeyThatIsNoEcKey() {
        String rsa = MonobankKeys.generateRsa().publicKeyBase64Pem();
        String garbage = Base64.getEncoder().encodeToString("no key at all".getBytes(StandardCharsets.US_ASCII));

        assertThatIllegalStateException().isThrownBy(() -> MonobankSignatures.parsePublicKey(rsa))
                .withMessageContaining("can't be read");
        assertThatIllegalStateException().isThrownBy(() -> MonobankSignatures.parsePublicKey(garbage))
                .withMessageContaining("can't be read");
    }
}
