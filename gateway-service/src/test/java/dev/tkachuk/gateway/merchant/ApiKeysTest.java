package dev.tkachuk.gateway.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

    @Test
    void shouldHashWithSha256AsLowercaseHex() {
        // The SHA-256 test vector for "abc" from FIPS 180-2.
        assertThat(ApiKeys.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
