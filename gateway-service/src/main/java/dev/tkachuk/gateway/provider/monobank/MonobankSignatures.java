package dev.tkachuk.gateway.provider.monobank;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * How monobank signs a webhook: ECDSA with SHA-256 over the body's exact bytes. The X-Sign header is the Base64 of
 * the signature in DER form, and the public key comes as the Base64 of a PEM "PUBLIC KEY" block.
 */
final class MonobankSignatures {

    private MonobankSignatures() {
    }

    /** The key from GET /api/merchant/pubkey; IllegalStateException when it is no EC public key. */
    static PublicKey parsePublicKey(String base64Pem) {
        try {
            String pem = new String(Base64.getMimeDecoder().decode(base64Pem), StandardCharsets.US_ASCII);
            String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "");
            byte[] der = Base64.getMimeDecoder().decode(body);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("monobank's public key can't be read: " + e.getMessage(), e);
        }
    }

    /** Whether the signature is the key's over these bytes. A signature that isn't Base64 or DER is no match. */
    static boolean isValid(PublicKey key, byte[] body, String signatureBase64) {
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(body);
            return verifier.verify(signature);
        } catch (SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A webhook signature can't be checked: " + e.getMessage(), e);
        }
    }
}
