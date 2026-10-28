package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BffSessionHandles")
class BffSessionHandlesTest {

    private static byte[] key(byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return key;
    }

    private static BffTokenCipher cipher(byte fill) {
        return new BffTokenCipher(Map.of("k1", key(fill)), "k1");
    }

    @Test
    @DisplayName(
            "GIVEN the same encryption key WHEN deriving twice SHOULD give the same handles, none of them the store key")
    void given_sameKey_when_deriving_should_beStable() {
        BffSessionHandles a = new BffSessionHandles(cipher((byte) 1).derive(BffSessionHandles.LABEL));
        BffSessionHandles b = new BffSessionHandles(cipher((byte) 1).derive(BffSessionHandles.LABEL));

        assertThat(a.handle("store-key")).isEqualTo(b.handle("store-key")).isNotEqualTo("store-key");
        assertThat(a.handle("store-key")).isNotEqualTo(a.handle("other-key"));
        assertThat(a.matches(b.handle("store-key"), "store-key")).isTrue();
        assertThat(a.matches(b.handle("store-key"), "other-key")).isFalse();
        assertThat(a.matches(null, "store-key")).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN another encryption key or label WHEN deriving SHOULD give other keys and never the encryption key itself")
    void given_otherKeyOrLabel_when_deriving_should_differ() {
        byte[] derived = cipher((byte) 1).derive(BffSessionHandles.LABEL);

        assertThat(derived).hasSize(32);
        assertThat(derived).isNotEqualTo(cipher((byte) 2).derive(BffSessionHandles.LABEL));
        assertThat(derived).isNotEqualTo(cipher((byte) 1).derive("another-label"));
        assertThat(derived).isNotEqualTo(key((byte) 1));
        assertThat(new BffSessionHandles(derived).handle("k"))
                .matches("[A-Za-z0-9_-]{43}")
                .isNotEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(derived));
    }

    @Test
    @DisplayName("GIVEN no encryption WHEN building SHOULD key the handles randomly per instance")
    void given_noEncryption_when_building_should_beRandomPerInstance() {
        BffProperties.Session session = new BffProperties.Session();

        assertThat(BffSessionHandles.from(session).handle("k"))
                .isNotEqualTo(BffSessionHandles.from(session).handle("k"));
    }
}
