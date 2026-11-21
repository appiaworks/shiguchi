package dev.appiaworks.shiguchi.backend.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import dev.appiaworks.shiguchi.backend.MaskSpec;
import dev.appiaworks.shiguchi.backend.PageCursor;
import dev.appiaworks.shiguchi.backend.PageDefaults;
import dev.appiaworks.shiguchi.backend.PageResult;
import dev.appiaworks.shiguchi.backend.PageWindow;
import dev.appiaworks.shiguchi.backend.SortSpec;
import dev.appiaworks.shiguchi.proto.pagination.PageRequest;
import dev.appiaworks.shiguchi.proto.pagination.SortField;
import dev.appiaworks.shiguchi.proto.pagination.SortOrder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.jpa.HibernatePersistenceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A real Hibernate on in-memory H2: the query it builds has to be one a database accepts. */
@DisplayName("ProjectionQuery")
class ProjectionQueryTest {

    @Entity(name = "Book")
    public static class BookEntity {
        @Id
        public String id;

        public String title;
        public String author;

        // "year" is a reserved word in H2 and several other databases
        @Column(name = "published_year")
        public int year;

        public BookEntity() {}

        BookEntity(String id, String title, String author, int year) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.year = year;
        }
    }

    private static final Descriptor BOOK = bookDescriptor();

    private static Descriptor bookDescriptor() {
        try {
            DescriptorProto.Builder message = DescriptorProto.newBuilder().setName("Book");
            int n = 1;
            for (String s : List.of("id", "title", "author")) {
                message.addField(FieldDescriptorProto.newBuilder()
                        .setName(s)
                        .setNumber(n++)
                        .setType(Type.TYPE_STRING)
                        .setLabel(Label.LABEL_OPTIONAL));
            }
            message.addField(FieldDescriptorProto.newBuilder()
                    .setName("year")
                    .setNumber(n)
                    .setType(Type.TYPE_INT32)
                    .setLabel(Label.LABEL_OPTIONAL));
            return FileDescriptor.buildFrom(
                            FileDescriptorProto.newBuilder()
                                    .setName("book.proto")
                                    .setPackage("t")
                                    .setSyntax("proto3")
                                    .addMessageType(message)
                                    .build(),
                            new FileDescriptor[0])
                    .findMessageTypeByName("Book");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final PageDefaults PAGING = PageDefaults.of(3, 50);
    private static final SortSpec SORT = SortSpec.of("id", "title", "author", "year");
    private static final MaskSpec MASK =
            MaskSpec.of("id", "title", "author", "year").alwaysInclude("id");

    private static EntityManagerFactory emf;
    private EntityManager em;

    @BeforeAll
    static void startDatabase() {
        emf = new HibernatePersistenceConfiguration("backend-support-test")
                .managedClass(BookEntity.class)
                .jdbcUrl("jdbc:h2:mem:backend-support;DB_CLOSE_DELAY=-1")
                .jdbcUsername("sa")
                .jdbcPassword("")
                .property("hibernate.hbm2ddl.auto", "create-drop")
                .createEntityManagerFactory();
        try (EntityManager seed = emf.createEntityManager()) {
            seed.getTransaction().begin();
            // ids are zero-padded so their string order is their numeric order
            String[][] rows = {
                {"01", "Dune", "Herbert", "1965"},
                {"02", "Emma", "Austen", "1815"},
                {"03", "Ubik", "Dick", "1969"},
                {"04", "Kim", "Kipling", "1901"},
                {"05", "Heat", "Mann", "2020"},
                {"06", "Beloved", "Morrison", "1987"},
                {"07", "Ulysses", "Joyce", "1922"},
            };
            for (String[] r : rows) {
                seed.persist(new BookEntity(r[0], r[1], r[2], Integer.parseInt(r[3])));
            }
            seed.getTransaction().commit();
        }
    }

    @AfterAll
    static void stopDatabase() {
        emf.close();
    }

    private ProjectionQuery<BookEntity, DynamicMessage> books() {
        em = emf.createEntityManager();
        return ProjectionQuery.of(em, BookEntity.class, DynamicMessage.getDefaultInstance(BOOK));
    }

    private static PageRequest request(int size, String token) {
        return PageRequest.newBuilder().setPageSize(size).setPageToken(token).build();
    }

    private static SortField sort(String field, SortOrder order) {
        return SortField.newBuilder().setField(field).setSortOrder(order).build();
    }

    private static List<String> ids(PageResult<DynamicMessage> page) {
        return page.items().stream()
                .map(m -> (String) m.getField(BOOK.findFieldByName("id")))
                .toList();
    }

    private static FieldMask mask(String... paths) {
        return FieldMask.newBuilder().addAllPaths(List.of(paths)).build();
    }

    @Test
    @DisplayName("GIVEN a mask WHEN fetched SHOULD fill only the masked fields and the always-included id")
    void given_mask_when_fetch_should_projectOnlyThoseColumns() {
        PageResult<DynamicMessage> page = books().select(MASK.resolve(mask("title")))
                .sort(SORT.resolve(List.of()))
                .fetch(PageWindow.of(request(2, ""), PAGING));

        DynamicMessage first = page.items().get(0);
        assertThat(first.getField(BOOK.findFieldByName("title"))).isEqualTo("Dune");
        assertThat(first.getField(BOOK.findFieldByName("id"))).isEqualTo("01");
        assertThat(first.getField(BOOK.findFieldByName("author"))).isEqualTo(""); // not fetched
        assertThat(first.getField(BOOK.findFieldByName("year"))).isEqualTo(0);
    }

    @Test
    @DisplayName("GIVEN a sort by year descending WHEN paged by offset SHOULD walk every row once, in order")
    void given_sortAndOffset_when_paged_should_visitEachRowOnce() {
        var query = books().select(MASK.all()).sort(SORT.resolve(List.of(sort("year", SortOrder.SORT_ORDER_DESC))));
        List<String> seen = new ArrayList<>();
        String token = "";
        int pages = 0;
        do {
            PageResult<DynamicMessage> page = query.fetch(PageWindow.of(request(3, token), PAGING));
            seen.addAll(ids(page));
            token = page.nextPageToken();
            pages++;
        } while (!token.isEmpty());

        assertThat(seen)
                .containsExactly("05", "06", "03", "01", "07", "04", "02"); // 2020, 1987, 1969, 1965, 1922, 1901, 1815
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN a filter WHEN fetched SHOULD only return matches and count them")
    void given_where_when_fetch_should_filterAndCount() {
        var query = books().select(MASK.all())
                .sort(SORT.resolve(List.of()))
                .where((root, cb) -> cb.greaterThan(root.get("year"), 1950));

        assertThat(ids(query.fetch(PageWindow.of(request(50, ""), PAGING)))).containsExactly("01", "03", "05", "06");
        assertThat(query.count()).isEqualTo(4);
    }

    @Test
    @DisplayName(
            "GIVEN a mixed-direction sort WHEN paged by keyset SHOULD match the offset order with no gaps or repeats")
    void given_mixedSort_when_keysetPaged_should_matchOffsetOrder() {
        var sorted = books().select(MASK.all())
                .sort(SORT.resolve(
                        List.of(sort("author", SortOrder.SORT_ORDER_DESC), sort("year", SortOrder.SORT_ORDER_ASC))));
        PageCursor cursor = PageCursor.signed("a-test-secret-of-length".getBytes());

        List<String> byKeyset = new ArrayList<>();
        String token = "";
        do {
            PageResult<DynamicMessage> page = sorted.fetch(PageWindow.keyset(request(2, token), PAGING, cursor));
            byKeyset.addAll(ids(page));
            token = page.nextPageToken();
        } while (!token.isEmpty());

        List<String> byOffset = ids(sorted.fetch(PageWindow.of(request(50, ""), PAGING)));
        assertThat(byKeyset).hasSize(7).containsExactlyElementsOf(byOffset);
    }

    @Test
    @DisplayName("GIVEN a keyset sort that the mask did not select WHEN paged SHOULD still build the cursor")
    void given_sortColumnNotInMask_when_keyset_should_stillPage() {
        var sorted = books().select(MASK.resolve(mask("title")))
                .sort(SORT.resolve(List.of(sort("year", SortOrder.SORT_ORDER_ASC))));

        PageResult<DynamicMessage> first =
                sorted.fetch(PageWindow.keyset(request(3, ""), PAGING, PageCursor.unsigned()));
        PageResult<DynamicMessage> second =
                sorted.fetch(PageWindow.keyset(request(3, first.nextPageToken()), PAGING, PageCursor.unsigned()));

        assertThat(ids(first)).containsExactly("02", "04", "07");
        assertThat(ids(second)).containsExactly("01", "03", "06");
    }

    @Test
    @DisplayName("GIVEN a keyset token from another sort WHEN fetched SHOULD be INVALID_ARGUMENT")
    void given_tokenFromOtherSort_when_fetch_should_beInvalidArgument() {
        PageCursor cursor = PageCursor.unsigned();
        var byYear = books().select(MASK.all()).sort(SORT.resolve(List.of(sort("year", SortOrder.SORT_ORDER_ASC))));
        var byTitle = books().select(MASK.all()).sort(SORT.resolve(List.of(sort("title", SortOrder.SORT_ORDER_ASC))));
        var byYearDesc =
                books().select(MASK.all()).sort(SORT.resolve(List.of(sort("year", SortOrder.SORT_ORDER_DESC))));
        String token =
                byYear.fetch(PageWindow.keyset(request(2, ""), PAGING, cursor)).nextPageToken();

        // Both sorts have the same number of keys (property + id), so only the sort fingerprint in
        // the token can tell the two apart.
        for (var other : List.of(byTitle, byYearDesc)) {
            assertThatThrownBy(() -> other.fetch(PageWindow.keyset(request(2, token), PAGING, cursor)))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        }
    }

    @Test
    @DisplayName("GIVEN fetch before select WHEN called SHOULD explain what is missing")
    void given_noSelect_when_fetch_should_explain() {
        assertThatThrownBy(() -> books().fetch(PageWindow.of(request(2, ""), PAGING)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("select");
    }

    @Test
    @DisplayName("GIVEN a UUID sort key WHEN decoded SHOULD round-trip through the cursor text")
    void given_uuid_when_roundTrip_should_beEqual() {
        UUID id = UUID.randomUUID();

        assertThat(KeyValues.decode(UUID.class, KeyValues.encode(id))).isEqualTo(id);
        assertThat(KeyValues.decode(int.class, KeyValues.encode(7))).isEqualTo(7);
        assertThatThrownBy(() -> KeyValues.decode(Integer.class, "abc"))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
