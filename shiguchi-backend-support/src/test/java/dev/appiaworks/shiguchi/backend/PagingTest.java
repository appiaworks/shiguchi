package dev.appiaworks.shiguchi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.proto.pagination.PageInfo;
import dev.appiaworks.shiguchi.proto.pagination.PageRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PageCursor, PageWindow and PageResult")
class PagingTest {

    private static final PageDefaults DEFAULTS = PageDefaults.of(25, 200);
    private static final byte[] SECRET = "0123456789abcdef-secret".getBytes();

    private static PageRequest request(int size, String token) {
        return PageRequest.newBuilder().setPageSize(size).setPageToken(token).build();
    }

    private static void assertInvalidArgument(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("GIVEN a size of 0 or above the ceiling WHEN windowed SHOULD use the default or clamp to the maximum")
    void given_oddSizes_when_window_should_clamp() {
        assertThat(PageWindow.of(request(0, ""), DEFAULTS).limit()).isEqualTo(25);
        assertThat(PageWindow.of(request(-3, ""), DEFAULTS).limit()).isEqualTo(25);
        assertThat(PageWindow.of(request(10_000, ""), DEFAULTS).limit()).isEqualTo(200);
        assertThat(PageWindow.of(request(7, ""), DEFAULTS).fetchSize()).isEqualTo(8);
    }

    @Test
    @DisplayName("GIVEN a full result WHEN trimmed SHOULD cut the extra row and issue the next offset")
    void given_extraRow_when_trim_should_issueNextOffset() {
        PageWindow first = PageWindow.of(request(2, ""), DEFAULTS);

        PageResult<String> page = first.trim(List.of("a", "b", "c"));

        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.hasMore()).isTrue();
        PageWindow second = PageWindow.of(request(2, page.nextPageToken()), DEFAULTS);
        assertThat(second.offset()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN the last page WHEN trimmed SHOULD leave the next token blank")
    void given_lastPage_when_trim_should_haveNoToken() {
        PageResult<String> page = PageWindow.of(request(2, ""), DEFAULTS).trim(List.of("a", "b"));

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextPageToken()).isEmpty();
        assertThat(page.toPageInfo().getNextPageToken()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a total WHEN page info is built SHOULD set total_count, and otherwise leave it at zero")
    void given_total_when_pageInfo_should_setCount() {
        PageResult<String> page = new PageResult<>(List.of(), "tok");

        PageInfo withTotal = page.toPageInfo(1284);

        assertThat(withTotal.getTotalCount()).isEqualTo(1284);
        assertThat(withTotal.getNextPageToken()).isEqualTo("tok");
        assertThat(page.toPageInfo().getTotalCount()).isZero();
    }

    @Test
    @DisplayName("GIVEN keyset mode WHEN trimmed SHOULD carry the last kept row's key into the next window")
    void given_keyset_when_trim_should_carryKey() {
        PageCursor cursor = PageCursor.signed(SECRET);
        PageWindow first = PageWindow.keyset(request(2, ""), DEFAULTS, cursor);
        assertThat(first.after()).isEmpty();

        PageResult<Map<String, String>> page =
                first.trim(List.of(Map.of("k0", "1"), Map.of("k0", "2"), Map.of("k0", "3")), row -> row);

        PageWindow second = PageWindow.keyset(request(2, page.nextPageToken()), DEFAULTS, cursor);
        assertThat(second.after()).containsEntry("k0", "2");
        assertThat(second.isKeyset()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a keyset window WHEN trimmed without keys SHOULD refuse rather than page wrongly")
    void given_keyset_when_trimWithoutKeys_should_refuse() {
        PageWindow window = PageWindow.keyset(request(2, ""), DEFAULTS, PageCursor.unsigned());

        assertThatThrownBy(() -> window.trim(List.of("a"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a garbage, truncated or negative-offset token WHEN decoded SHOULD be INVALID_ARGUMENT or the first page")
    void given_badTokens_when_decode_should_reject() {
        PageCursor cursor = PageCursor.unsigned();

        assertInvalidArgument(() -> cursor.decodeOffset("not base64 !!"));
        assertInvalidArgument(() -> cursor.decodeOffset("AAAA"));
        assertInvalidArgument(() -> cursor.decodeOffset("x".repeat(5000)));
        assertThat(cursor.decodeOffset(cursor.encodeOffset(-5))).isZero();
        assertThat(cursor.decodeOffset(null)).isZero();
        assertThat(cursor.decodeOffset("  ")).isZero();
    }

    @Test
    @DisplayName("GIVEN a signed cursor WHEN the token is tampered with or unsigned SHOULD be rejected")
    void given_signedCursor_when_tampered_should_reject() {
        PageCursor signed = PageCursor.signed(SECRET);
        PageCursor otherKey = PageCursor.signed("a-completely-other-secret".getBytes());
        String token = signed.encodeOffset(50);

        assertThat(signed.decodeOffset(token)).isEqualTo(50);
        assertInvalidArgument(() -> otherKey.decodeOffset(token));
        assertInvalidArgument(() -> signed.decodeOffset(PageCursor.unsigned().encodeOffset(50)));
        assertInvalidArgument(() -> signed.decodeOffset(token.substring(0, token.length() - 2) + "AA"));
        assertInvalidArgument(() -> PageCursor.unsigned().decodeOffset(token));
    }

    @Test
    @DisplayName("GIVEN a weak secret WHEN a signed cursor is built SHOULD refuse it")
    void given_shortSecret_when_signed_should_refuse() {
        assertThatThrownBy(() -> PageCursor.signed(new byte[4])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GIVEN invalid page sizes WHEN defaults are built SHOULD refuse them")
    void given_badDefaults_when_build_should_refuse() {
        assertThatThrownBy(() -> PageDefaults.of(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageDefaults.of(50, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
