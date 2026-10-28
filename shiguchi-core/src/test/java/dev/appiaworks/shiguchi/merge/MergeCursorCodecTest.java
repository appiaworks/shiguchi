package dev.appiaworks.shiguchi.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.merge.MergeCursor.SourcePosition;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the composite cursor: round-tripping merged position across N sources, and refusing
 * every cursor whose meaning cannot be trusted.
 */
class MergeCursorCodecTest {

    @Test
    @DisplayName("GIVEN a cursor over several sources WHEN encoded and decoded SHOULD round-trip exactly")
    void given_cursorWithNSources_when_roundTripped_should_matchExactly() {
        MergeCursor original = new MergeCursor(
                "42",
                List.of(
                        new SourcePosition("tokenA", 2),
                        new SourcePosition("tokenB", 0),
                        new SourcePosition("tokenC", 7)));

        MergeCursor decoded = MergeCursorCodec.decode(MergeCursorCodec.encode(original), 3);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("GIVEN an exhausted source WHEN round-tripped SHOULD preserve the exhausted marker")
    void given_exhaustedSource_when_roundTripped_should_preserveMarker() {
        MergeCursor original =
                new MergeCursor("9", List.of(new SourcePosition("live", 1), SourcePosition.exhaustedSource()));

        MergeCursor decoded = MergeCursorCodec.decode(MergeCursorCodec.encode(original), 2);

        assertThat(decoded.sources().get(1).exhausted()).isTrue();
        assertThat(decoded.sources().get(0).exhausted()).isFalse();
        assertThat(decoded.exhausted()).isFalse();
    }

    @Test
    @DisplayName("GIVEN every source exhausted WHEN checking the cursor SHOULD report the merge finished")
    void given_allSourcesExhausted_when_checking_should_reportExhausted() {
        MergeCursor cursor =
                new MergeCursor("9", List.of(SourcePosition.exhaustedSource(), SourcePosition.exhaustedSource()));

        assertThat(cursor.exhausted()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a backend token with non-ASCII bytes WHEN round-tripped SHOULD survive intact")
    void given_nonAsciiToken_when_roundTripped_should_survive() {
        String awkward = "tök//en+=?&é中文";
        MergeCursor original = new MergeCursor("k", List.of(new SourcePosition(awkward, 0), new SourcePosition("", 0)));

        MergeCursor decoded = MergeCursorCodec.decode(MergeCursorCodec.encode(original), 2);

        assertThat(decoded.sources().get(0).token()).isEqualTo(awkward);
    }

    @Test
    @DisplayName("GIVEN any cursor WHEN encoded SHOULD be URL-safe so it survives a query parameter")
    void given_anyCursor_when_encoded_should_beUrlSafe() {
        String encoded = MergeCursorCodec.encode(
                new MergeCursor("k", List.of(new SourcePosition("a/b+c=d", 1), new SourcePosition("e?f&g", 2))));

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("GIVEN large repetitive backend tokens WHEN encoded SHOULD compress below the size cap")
    void given_largeTokens_when_encoded_should_stayUnderCap() {
        String big = "x".repeat(4000);
        String encoded = MergeCursorCodec.encode(new MergeCursor(
                "k", List.of(new SourcePosition(big, 0), new SourcePosition(big, 0), new SourcePosition(big, 0))));

        assertThat(encoded.length()).isLessThanOrEqualTo(MergeCursorCodec.MAX_ENCODED_LENGTH);
    }

    @Test
    @DisplayName("GIVEN incompressible tokens far over the cap WHEN encoding SHOULD fail loudly rather than "
            + "emit a cursor a proxy would truncate")
    void given_oversizedCursor_when_encoding_should_fail() {
        // Genuinely random content, so gzip has no redundancy to exploit and the cap is reached.
        java.util.Random random = new java.util.Random(20260907L);
        List<SourcePosition> sources = new java.util.ArrayList<>();
        for (int s = 0; s < 4; s++) {
            StringBuilder noise = new StringBuilder(4000);
            for (int i = 0; i < 4000; i++) {
                noise.append((char) ('a' + random.nextInt(26)));
            }
            sources.add(new SourcePosition(noise.toString(), 0));
        }

        assertThatThrownBy(() -> MergeCursorCodec.encode(new MergeCursor("k", List.copyOf(sources))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over the");
    }

    // ===== rejection: a cursor that cannot be trusted is never repaired =====

    @Test
    @DisplayName("GIVEN a cursor for a different source count WHEN decoding SHOULD reject it")
    void given_wrongSourceCount_when_decoding_should_reject() {
        String encoded = MergeCursorCodec.encode(
                new MergeCursor("k", List.of(new SourcePosition("a", 0), new SourcePosition("b", 0))));

        assertThatThrownBy(() -> MergeCursorCodec.decode(encoded, 3))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class)
                .hasMessageContaining("different route");
    }

    @Test
    @DisplayName("GIVEN a truncated cursor WHEN decoding SHOULD reject rather than resume at the wrong place")
    void given_truncatedCursor_when_decoding_should_reject() {
        String encoded = MergeCursorCodec.encode(
                new MergeCursor("k", List.of(new SourcePosition("a", 0), new SourcePosition("b", 0))));

        assertThatThrownBy(() -> MergeCursorCodec.decode(encoded.substring(0, encoded.length() / 2), 2))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class);
    }

    @Test
    @DisplayName("GIVEN a non-base64 cursor WHEN decoding SHOULD reject it")
    void given_nonBase64Cursor_when_decoding_should_reject() {
        assertThatThrownBy(() -> MergeCursorCodec.decode("not a cursor!!!", 2))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class)
                .hasMessageContaining("well-formed");
    }

    @Test
    @DisplayName("GIVEN valid base64 that is not a gateway cursor WHEN decoding SHOULD reject it")
    void given_base64OfSomethingElse_when_decoding_should_reject() {
        String notACursor = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> MergeCursorCodec.decode(notACursor, 2))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class);
    }

    @Test
    @DisplayName("GIVEN an empty or null cursor WHEN decoding SHOULD reject it")
    void given_emptyCursor_when_decoding_should_reject() {
        assertThatThrownBy(() -> MergeCursorCodec.decode("", 2))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> MergeCursorCodec.decode(null, 2))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class);
    }

    @Test
    @DisplayName("GIVEN a cursor longer than the cap WHEN decoding SHOULD reject before attempting to inflate it")
    void given_overlongCursor_when_decoding_should_reject() {
        assertThatThrownBy(() -> MergeCursorCodec.decode("A".repeat(MergeCursorCodec.MAX_ENCODED_LENGTH + 1), 2))
                .isInstanceOf(MergeCursorCodec.InvalidCursorException.class)
                .hasMessageContaining("maximum supported length");
    }

    @Test
    @DisplayName("GIVEN a negative tie count WHEN constructing a position SHOULD reject it")
    void given_negativeTieCount_when_constructing_should_reject() {
        assertThatThrownBy(() -> new SourcePosition("token", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
