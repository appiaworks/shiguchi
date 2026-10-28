package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The backward half of Relay's {@code PageInfo}.
 *
 * <p>{@code hasPreviousPage} and {@code startCursor} were hardcoded to {@code false} and
 * {@code null}, so the schema advertised them and then always denied there was anything behind —
 * wrong for every caller past the first page. They now come from the backend's
 * {@code prev_page_token} when it provides one, and from the cursor the caller paged in on
 * otherwise.
 */
@DisplayName("Relay backward pagination")
class RelayBackwardPaginationTest {

    private final RelayConnectionTransformer transformer = new RelayConnectionTransformer();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfo(Map<String, Object> connection) {
        return (Map<String, Object>) connection.get("pageInfo");
    }

    /** A gRPC response with the documented page_info shape. */
    private static Map<String, Object> response(Map<String, Object> pageInfo) {
        return Map.of("items", List.of(Map.of("id", "1")), "pageInfo", pageInfo);
    }

    @Test
    @DisplayName("GIVEN the first page WHEN transformed SHOULD report no previous page, since there is none")
    void given_firstPage_when_transformed_should_reportNoPrevious() {
        Map<String, Object> connection = transformer.transform(response(Map.of("nextPageToken", "n1")), "items", null);

        assertThat(pageInfo(connection)).containsEntry("hasPreviousPage", false).containsEntry("startCursor", null);
        assertThat(pageInfo(connection)).containsEntry("hasNextPage", true).containsEntry("endCursor", "n1");
    }

    @Test
    @DisplayName(
            "GIVEN the caller paged in on a cursor WHEN transformed SHOULD report a previous page, which is what was wrong before")
    void given_pagedInOnCursor_when_transformed_should_reportPrevious() {
        // The regression this fixes: arriving here on `after` means there is a page behind, and
        // the old code said otherwise no matter what.
        Map<String, Object> connection =
                transformer.transform(response(Map.of("nextPageToken", "n2")), "items", "cursor-from-page-1");

        assertThat(pageInfo(connection))
                .containsEntry("hasPreviousPage", true)
                .containsEntry("startCursor", "cursor-from-page-1");
    }

    @Test
    @DisplayName("GIVEN the backend supplies prev_page_token WHEN transformed SHOULD prefer it over the request cursor")
    void given_backendPrevToken_when_transformed_should_preferIt() {
        // A backend that computes a real backward cursor knows better than the gateway's
        // inference: its token is a position the caller can page back to.
        Map<String, Object> connection = transformer.transform(
                response(Map.of("nextPageToken", "n3", "prevPageToken", "p2")), "items", "cursor-from-page-1");

        assertThat(pageInfo(connection)).containsEntry("hasPreviousPage", true).containsEntry("startCursor", "p2");
    }

    @Test
    @DisplayName(
            "GIVEN a backend prev_page_token on an un-cursored request WHEN transformed SHOULD still report a previous page")
    void given_backendPrevTokenWithoutRequestCursor_when_transformed_should_reportPrevious() {
        Map<String, Object> connection =
                transformer.transform(response(Map.of("nextPageToken", "", "prevPageToken", "p1")), "items", null);

        assertThat(pageInfo(connection)).containsEntry("hasPreviousPage", true).containsEntry("startCursor", "p1");
    }

    @Test
    @DisplayName("GIVEN a blank cursor WHEN transformed SHOULD treat it as no cursor rather than as a page behind")
    void given_blankCursor_when_transformed_should_treatAsNone() {
        Map<String, Object> connection = transformer.transform(response(Map.of("nextPageToken", "n1")), "items", "  ");

        assertThat(pageInfo(connection)).containsEntry("hasPreviousPage", false).containsEntry("startCursor", null);
    }

    @Test
    @DisplayName("GIVEN the last page reached by paging WHEN transformed SHOULD report a previous page and no next one")
    void given_lastPage_when_transformed_should_reportPreviousOnly() {
        Map<String, Object> connection =
                transformer.transform(response(Map.of("nextPageToken", "")), "items", "cursor-from-page-2");

        assertThat(pageInfo(connection))
                .containsEntry("hasNextPage", false)
                .containsEntry("endCursor", null)
                .containsEntry("hasPreviousPage", true);
    }

    @Test
    @DisplayName("GIVEN the two-argument form WHEN called SHOULD behave as before, so existing callers are unaffected")
    void given_twoArgForm_when_called_should_beUnchanged() {
        Map<String, Object> connection = transformer.transform(response(Map.of("nextPageToken", "n1")), "items");

        assertThat(pageInfo(connection)).containsEntry("hasPreviousPage", false).containsEntry("hasNextPage", true);
    }
}
