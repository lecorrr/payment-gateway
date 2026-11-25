package dev.tkachuk.acquirer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * The key acquirer-sim signs its monobank-style webhooks with, as monobank does: ECDSA on P-256 with SHA-256. A new
 * one is made at every start; GET /api/merchant/pubkey hands out the public half.
 */
@Component
class MonobankKeys {

    private final KeyPair keyPair;

    MonobankKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This JVM can't make P-256 keys", e);
        }
    }

    PublicKey publicKey() {
        return keyPair.getPublic();
    }

    /** The public key as monobank hands out its own: Base64 of a PEM block. */
    String publicKeyBase64Pem() {
        Base64.Encoder lines = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        String pem = "-----BEGIN PUBLIC KEY-----\n" + lines.encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }

    /** The X-Sign header of a webhook: the DER signature of the body, in Base64. */
    String sign(byte[] body) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(body);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A webhook could not be signed", e);
        }
    }
}
