package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BffTokenCipher: AES-256-GCM of one stored token")
class BffTokenCipherTest {

    private static final byte[] K1 = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] K2 = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.payload.signature";

    private static BffTokenCipher cipher(String active) {
        return new BffTokenCipher(Map.of("k1", K1, "k2", K2), active);
    }

    @Test
    @DisplayName("GIVEN a token WHEN encrypted and decrypted for the same row and field SHOULD return it")
    void given_token_when_round_trip_should_return_it() {
        BffTokenCipher cipher = cipher("k1");

        String stored = cipher.encrypt("row", "access_token", TOKEN);

        assertThat(stored).startsWith("v1.k1.").doesNotContain(TOKEN);
        assertThat(cipher.decrypt("row", "access_token", stored)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("GIVEN the same token WHEN encrypted twice SHOULD differ because the IV is random")
    void given_same_token_when_encrypted_twice_should_differ() {
        BffTokenCipher cipher = cipher("k1");

        assertThat(cipher.encrypt("row", "access_token", TOKEN))
                .isNotEqualTo(cipher.encrypt("row", "access_token", TOKEN));
    }

    @Test
    @DisplayName("GIVEN null or empty WHEN encrypted or decrypted SHOULD pass through")
    void given_null_or_empty_when_processed_should_pass_through() {
        BffTokenCipher cipher = cipher("k1");

        assertThat(cipher.encrypt("row", "id_token", null)).isNull();
        assertThat(cipher.encrypt("row", "id_token", "")).isEmpty();
        assertThat(cipher.decrypt("row", "id_token", null)).isNull();
        assertThat(cipher.decrypt("row", "id_token", "")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a ciphertext of another session key WHEN decrypted SHOULD be rejected")
    void given_ciphertext_moved_to_other_row_when_decrypted_should_be_rejected() {
        BffTokenCipher cipher = cipher("k1");
        String stored = cipher.encrypt("row-a", "access_token", TOKEN);

        assertThat(failure(cipher, "row-b", "access_token", stored)).isEqualTo(BffTokenCipher.Failure.REJECTED);
    }

    @Test
    @DisplayName("GIVEN a ciphertext of another field WHEN decrypted SHOULD be rejected")
    void given_ciphertext_moved_to_other_field_when_decrypted_should_be_rejected() {
        BffTokenCipher cipher = cipher("k1");
        String stored = cipher.encrypt("row", "access_token", TOKEN);

        assertThat(failure(cipher, "row", "refresh_token", stored)).isEqualTo(BffTokenCipher.Failure.REJECTED);
    }

    @Test
    @DisplayName("GIVEN a ciphertext with a flipped tag bit WHEN decrypted SHOULD be rejected")
    void given_tampered_tag_when_decrypted_should_be_rejected() {
        BffTokenCipher cipher = cipher("k1");
        String stored = cipher.encrypt("row", "access_token", TOKEN);
        int dot = stored.lastIndexOf('.');
        byte[] raw = Base64.getUrlDecoder().decode(stored.substring(dot + 1));
        raw[raw.length - 1] ^= 1;
        String tampered = stored.substring(0, dot + 1)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        assertThat(failure(cipher, "row", "access_token", tampered)).isEqualTo(BffTokenCipher.Failure.REJECTED);
    }

    @Test
    @DisplayName("GIVEN a key id that is not configured WHEN decrypted SHOULD fail as unknown key")
    void given_unknown_kid_when_decrypted_should_fail_as_unknown_key() {
        String stored = cipher("k2").encrypt("row", "access_token", TOKEN);
        BffTokenCipher onlyK1 = new BffTokenCipher(Map.of("k1", K1), "k1");

        assertThat(failure(onlyK1, "row", "access_token", stored)).isEqualTo(BffTokenCipher.Failure.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("GIVEN a plaintext value WHEN decrypted SHOULD fail as not encrypted")
    void given_plaintext_when_decrypted_should_fail_as_not_encrypted() {
        assertThat(failure(cipher("k1"), "row", "access_token", TOKEN)).isEqualTo(BffTokenCipher.Failure.NOT_ENCRYPTED);
    }

    @Test
    @DisplayName("GIVEN malformed ciphertexts WHEN decrypted SHOULD fail as malformed")
    void given_malformed_values_when_decrypted_should_fail_as_malformed() {
        BffTokenCipher cipher = cipher("k1");

        assertThat(failure(cipher, "row", "access_token", "v1.k1")).isEqualTo(BffTokenCipher.Failure.MALFORMED);
        assertThat(failure(cipher, "row", "access_token", "v1.k1.!!!")).isEqualTo(BffTokenCipher.Failure.MALFORMED);
        assertThat(failure(cipher, "row", "access_token", "v1.k1.AAAA")).isEqualTo(BffTokenCipher.Failure.MALFORMED);
    }

    @Test
    @DisplayName(
            "GIVEN a value written under the old key WHEN the active key changes SHOULD still decrypt it and write the new id")
    void given_rotation_when_active_changes_should_read_old_and_write_new() {
        String old = cipher("k1").encrypt("row", "access_token", TOKEN);
        BffTokenCipher rotated = cipher("k2");

        assertThat(rotated.decrypt("row", "access_token", old)).isEqualTo(TOKEN);
        assertThat(rotated.encrypt("row", "access_token", TOKEN)).startsWith("v1.k2.");
    }

    @Test
    @DisplayName("GIVEN a decryption failure WHEN its message is read SHOULD hold neither the value nor a key")
    void given_failure_when_message_read_should_not_leak() {
        BffTokenCipher cipher = cipher("k1");
        String stored = cipher.encrypt("row", "access_token", TOKEN);

        assertThatThrownBy(() -> cipher.decrypt("other", "access_token", stored))
                .isInstanceOf(BffTokenCipher.DecryptionException.class)
                .hasMessageNotContaining(stored)
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining(new String(K1, StandardCharsets.UTF_8));
    }

    private static BffTokenCipher.Failure failure(BffTokenCipher cipher, String row, String field, String stored) {
        try {
            cipher.decrypt(row, field, stored);
        } catch (BffTokenCipher.DecryptionException e) {
            return e.failure();
        }
        throw new AssertionError("decrypted without failure");
    }
}
