package dev.appiaworks.shiguchi.sample.jpabackend;

import dev.appiaworks.shiguchi.backend.MaskSpec;
import dev.appiaworks.shiguchi.backend.PageCursor;
import dev.appiaworks.shiguchi.backend.PageDefaults;
import dev.appiaworks.shiguchi.backend.PageResult;
import dev.appiaworks.shiguchi.backend.PageWindow;
import dev.appiaworks.shiguchi.backend.SortSpec;
import dev.appiaworks.shiguchi.backend.UnaryCalls;
import dev.appiaworks.shiguchi.backend.jpa.ProjectionQuery;
import dev.appiaworks.shiguchi.sample.jpabackend.proto.Book;
import dev.appiaworks.shiguchi.sample.jpabackend.proto.BookServiceGrpc;
import dev.appiaworks.shiguchi.sample.jpabackend.proto.ListBooksRequest;
import dev.appiaworks.shiguchi.sample.jpabackend.proto.ListBooksResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The library backend: a gRPC service whose {@code ListBooks} honours the gateway's {@code
 * sort_fields}, {@code read_mask} and {@code page_request} with {@code
 * shiguchi-backend-support-jpa}.
 *
 * <p>In a real deployment this is a separate process. It runs in-process here so the sample is one
 * module with nothing else to start.
 */
@Component
public class BookBackend {

    private static final Logger log = LoggerFactory.getLogger(BookBackend.class);

    /** What a client may sort by. Proto field name on the left, JPA property on the right. */
    private static final SortSpec SORT = SortSpec.builder()
            .field("id")
            .field("title")
            .field("author")
            .field("published_year", "year")
            .build();

    /** What a client may select. {@code id} is always fetched: the cursor and the caller need it. */
    private static final MaskSpec MASK = MaskSpec.builder()
            .field("id")
            .field("title")
            .field("author")
            .field("published_year", "year")
            .build()
            .alwaysInclude("id");

    private static final PageDefaults PAGING = PageDefaults.of(5, 100);

    private final ProjectionQuery<BookEntity, Book> books;
    private final PageCursor cursor;
    private final boolean keyset;
    private final int port;
    private Server server;

    public BookBackend(
            EntityManager em,
            @Value("${sample.backend-port:9090}") int port,
            @Value("${sample.paging:offset}") String paging,
            @Value("${sample.cursor-secret:change-me-this-is-only-a-sample}") String cursorSecret) {
        this.books = ProjectionQuery.of(em, BookEntity.class, Book.getDefaultInstance());
        this.cursor = PageCursor.signed(cursorSecret.getBytes(StandardCharsets.UTF_8));
        this.keyset = paging.equalsIgnoreCase("keyset");
        this.port = port;
    }

    @PostConstruct
    public void start() throws Exception {
        server = ServerBuilder.forPort(port).addService(new BookService()).build().start();
        log.info("book backend listening on :{} ({} paging)", port, keyset ? "keyset" : "offset");
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.shutdownNow();
    }

    private final class BookService extends BookServiceGrpc.BookServiceImplBase {

        @Override
        public void listBooks(ListBooksRequest request, StreamObserver<ListBooksResponse> out) {
            // respond() turns a client mistake (an unsortable field, a forged token) into the call's
            // INVALID_ARGUMENT status, which the gateway answers as a 400. Without it grpc-java
            // would report the exception as UNKNOWN, and a 500.
            UnaryCalls.respond(out, () -> {
                PageWindow window = keyset
                        ? PageWindow.keyset(request.getPageRequest(), PAGING, cursor)
                        : PageWindow.of(request.getPageRequest(), PAGING, cursor);

                var query = books.select(MASK.resolve(request.getReadMask()))
                        .sort(SORT.resolve(request.getSortFieldsList()));
                PageResult<Book> page = query.fetch(window);

                return ListBooksResponse.newBuilder()
                        .addAllBooks(page.items())
                        .setPageInfo(page.toPageInfo(query.count())) // drop the count if nobody needs a total
                        .build();
            });
        }
    }
}
