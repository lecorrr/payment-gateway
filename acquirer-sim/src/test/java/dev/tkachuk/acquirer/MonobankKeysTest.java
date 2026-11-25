package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The keys as the gateway's MonobankSignatures reads them: a PEM block in Base64, and DER signatures. */
class MonobankKeysTest {

    private static final byte[] BODY = "{\"invoiceId\": \"ch_1\", \"status\": \"success\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final MonobankKeys keys = new MonobankKeys();

    @Test
    void shouldSignBodySoItsPublicKeyVerifiesIt() throws GeneralSecurityException {
        String signature = keys.sign(BODY);

        assertThat(verify(keys.publicKey(), BODY, signature)).isTrue();
        assertThat(verify(keys.publicKey(), "{}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
    }

    @Test
    void shouldHandOutPublicKeyAsPemInBase64() throws GeneralSecurityException {
        String pem = new String(Base64.getDecoder().decode(keys.publicKeyBase64Pem()), StandardCharsets.US_ASCII);

        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("\n-----END PUBLIC KEY-----\n");
        assertThat(pem.lines()).allMatch(line -> line.length() <= 64);
        String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "");
        PublicKey parsed = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(encoded)));
        assertThat(parsed).isEqualTo(keys.publicKey());
    }

    @Test
    void shouldMakeNewKeyEachStart() {
        assertThat(new MonobankKeys().publicKeyBase64Pem()).isNotEqualTo(keys.publicKeyBase64Pem());
    }

    private static boolean verify(PublicKey key, byte[] body, String signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(body);
        return verifier.verify(Base64.getDecoder().decode(signature));
    }
}
