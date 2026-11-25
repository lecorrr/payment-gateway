package dev.tkachuk.gateway.merchant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Merchants' API keys are stored only as SHA-256 hashes. The keys are long random strings, so a fast hash is
 * enough: there is nothing to guess, unlike with passwords.
 */
public final class ApiKeys {

    private ApiKeys() {
    }

    public static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
