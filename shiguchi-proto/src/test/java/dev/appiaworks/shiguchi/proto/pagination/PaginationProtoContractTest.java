package dev.appiaworks.shiguchi.proto.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shipped paging messages against the conventions that read them.
 *
 * <p>These messages are only useful if they match what the gateway matches on, and nothing
 * structural connects the two: the matchers look up fields by name on whatever message a user
 * wrote, and never mention these types. So a rename here would not break any compile — it would
 * quietly ship types that no longer activate the behaviour they exist to activate.
 *
 * <p>The names asserted below are the contract documented in docs/projections-paging-sorting.md
 * and implemented in {@code ConventionArgMapper} and {@code GrpcResponsePaginationExtractor}.
 * Changing one means changing all three deliberately.
 */
@DisplayName("Pagination proto contract")
class PaginationProtoContractTest {

    private static FieldDescriptor field(Descriptor message, String name) {
        return message.findFieldByName(name);
    }

    @Nested
    @DisplayName("PageRequest")
    class PageRequestContract {

        @Test
        @DisplayName("GIVEN the shipped PageRequest WHEN its fields are read SHOULD carry page_size and page_token")
        void given_shipped_when_readingFields_should_matchTheConvention() {
            Descriptor message = PageRequest.getDescriptor();

            assertThat(field(message, "page_size")).isNotNull();
            assertThat(field(message, "page_size").getJavaType()).isEqualTo(FieldDescriptor.JavaType.INT);
            assertThat(field(message, "page_token")).isNotNull();
            assertThat(field(message, "page_token").getJavaType()).isEqualTo(FieldDescriptor.JavaType.STRING);
        }
    }

    @Nested
    @DisplayName("PageInfo")
    class PageInfoContract {

        @Test
        @DisplayName(
                "GIVEN the shipped PageInfo WHEN its fields are read SHOULD carry the three names the extractor looks up")
        void given_shipped_when_readingFields_should_matchTheConvention() {
            Descriptor message = PageInfo.getDescriptor();

            assertThat(field(message, "next_page_token")).isNotNull();
            assertThat(field(message, "next_page_token").getJavaType()).isEqualTo(FieldDescriptor.JavaType.STRING);
            assertThat(field(message, "total_count")).isNotNull();
            assertThat(field(message, "total_count").getJavaType()).isEqualTo(FieldDescriptor.JavaType.INT);
            assertThat(field(message, "prev_page_token")).isNotNull();
            assertThat(field(message, "prev_page_token").getJavaType()).isEqualTo(FieldDescriptor.JavaType.STRING);
        }

        @Test
        @DisplayName(
                "GIVEN a blank next_page_token WHEN read back SHOULD stay blank, since that absence is how a caller sees the last page")
        void given_unsetToken_when_readBack_should_stayBlank() {
            // The gateway omits X-Next-Page-Token entirely when this is blank, so a default that
            // serialised as anything else would report a further page that does not exist.
            assertThat(PageInfo.getDefaultInstance().getNextPageToken()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Sorting")
    class SortContract {

        @Test
        @DisplayName("GIVEN the shipped SortField WHEN its fields are read SHOULD carry field and sort_order")
        void given_shipped_when_readingFields_should_matchTheConvention() {
            Descriptor message = SortField.getDescriptor();

            assertThat(field(message, "field")).isNotNull();
            assertThat(field(message, "field").getJavaType()).isEqualTo(FieldDescriptor.JavaType.STRING);
            assertThat(field(message, "sort_order")).isNotNull();
            assertThat(field(message, "sort_order").getJavaType()).isEqualTo(FieldDescriptor.JavaType.ENUM);
        }

        @Test
        @DisplayName(
                "GIVEN the SortOrder enum WHEN its values are read SHOULD spell them the way ?sort= is normalised to")
        void given_sortOrder_when_readingValues_should_useTheNormalisedSpellings() {
            // "?sort=year:desc" is normalised to SORT_ORDER_DESC before it reaches the backend,
            // and GraphQL emits these same names in its SortOrder enum.
            assertThat(SortOrder.SORT_ORDER_UNSPECIFIED.getNumber()).isZero();
            assertThat(SortOrder.SORT_ORDER_ASC.getNumber()).isEqualTo(1);
            assertThat(SortOrder.SORT_ORDER_DESC.getNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("GIVEN an unset sort_order WHEN read SHOULD be UNSPECIFIED, which the gateway treats as ascending")
        void given_unsetOrder_when_read_should_beUnspecified() {
            assertThat(SortField.getDefaultInstance().getSortOrder()).isEqualTo(SortOrder.SORT_ORDER_UNSPECIFIED);
        }
    }

    @Test
    @DisplayName("GIVEN the file WHEN its import path is read SHOULD be the path the docs tell users to import")
    void given_file_when_readingPath_should_matchTheDocumentedImport() {
        // Consumers write `import "shiguchi/pagination.proto"`, and both the jar and
        // the protos zip lay the file out at that path. If the source moves, that import breaks
        // for everyone while this module still compiles perfectly well.
        assertThat(PageRequest.getDescriptor().getFile().getName()).isEqualTo("shiguchi/pagination.proto");
    }
}
