package dev.tkachuk.gateway.provider.monobank;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A P-256 key pair in place of monobank's: the public key as GET /api/merchant/pubkey serves it, and signatures as
 * monobank puts them in X-Sign. TEST is shared by the integration tests, whose gateway keeps the first key it fetches.
 */
public final class MonobankKeys {

    public static final MonobankKeys TEST = generate();

    private final KeyPair keyPair;

    private MonobankKeys(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static MonobankKeys generate() {
        return generate("EC", new ECGenParameterSpec("secp256r1"));
    }

    static MonobankKeys generateRsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new MonobankKeys(generator.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MonobankKeys generate(String algorithm, ECGenParameterSpec curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            generator.initialize(curve);
            return new MonobankKeys(generator.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Base64 of the PEM block, its lines 64 characters long, as monobank sends it. */
    public String publicKeyBase64Pem() {
        String lines = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPublic().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + lines + "\n-----END PUBLIC KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }

    /** Base64 of the DER signature over the body, as X-Sign has it. */
    public String sign(byte[] body) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(body);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sign(String body) {
        return sign(body.getBytes(StandardCharsets.UTF_8));
    }
}
