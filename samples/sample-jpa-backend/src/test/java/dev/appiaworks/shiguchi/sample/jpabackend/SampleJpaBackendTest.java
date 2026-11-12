package dev.appiaworks.shiguchi.sample.jpabackend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The same assertions against both paging modes: the gateway contract (headers, ordering, no
 * gaps, no repeats) is identical whether the token holds an offset or a key.
 */
@DisplayName("JPA backend sample")
class SampleJpaBackendTest {

    abstract static class Contract {

        @LocalServerPort
        int port;

        WebTestClient client() {
            return WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();
        }

        @SuppressWarnings("unchecked")
        List<String> ids(String uri) {
            EntityExchangeResult<java.util.Map> result = client().get()
                    .uri(uri)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(java.util.Map.class)
                    .returnResult();
            return ((List<java.util.Map<String, Object>>) result.getResponseBody().get("books"))
                    .stream().map(b -> (String) b.get("id")).toList();
        }

        @Test
        @DisplayName("GIVEN sort=publishedYear:desc WHEN every page is followed SHOULD visit all ten books once, newest first")
        void given_sort_when_followingPages_should_visitEachBookOnce() {
            List<String> seen = new ArrayList<>();
            String token = "";
            int pages = 0;
            do {
                String uri = "/v1/books?pageSize=4&sort=publishedYear:desc" + (token.isEmpty() ? "" : "&pageToken=" + token);
                EntityExchangeResult<byte[]> result = client().get()
                        .uri(uri)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .valueEquals("X-Total-Count", "10")
                        .expectBody()
                        .jsonPath("$.books[0].id")
                        .exists()
                        .returnResult();
                seen.addAll(idsOf(result));
                token = result.getResponseHeaders().getFirst("X-Next-Page-Token");
                token = token == null ? "" : token;
                pages++;
            } while (!token.isEmpty());

            assertThat(pages).isEqualTo(3);
            // 2020, 1987, 1984, 1969, 1965, 1922, 1901, 1897, 1871, 1815
            assertThat(seen).containsExactly("05", "06", "09", "03", "01", "07", "04", "08", "10", "02");
        }

        @SuppressWarnings("unchecked")
        private static List<String> idsOf(EntityExchangeResult<byte[]> result) {
            try {
                var map = new tools.jackson.databind.ObjectMapper().readValue(result.getResponseBody(), java.util.Map.class);
                return ((List<java.util.Map<String, Object>>) map.get("books"))
                        .stream().map(b -> (String) b.get("id")).toList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Test
        @DisplayName("GIVEN fields=title WHEN listed SHOULD fetch the title and the always-included id, and leave the rest at their defaults")
        void given_fields_when_listed_should_projectOnlyThose() {
            client().get()
                    .uri("/v1/books?pageSize=1&fields=title")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.books[0].id")
                    .isEqualTo("01")
                    .jsonPath("$.books[0].title")
                    .isEqualTo("Dune")
                    // Not fetched, so never set: plain proto3 scalars still render as their defaults.
                    // (Mark a field `optional` and it is omitted instead.)
                    .jsonPath("$.books[0].author")
                    .isEqualTo("")
                    .jsonPath("$.books[0].publishedYear")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("GIVEN no sort WHEN listed SHOULD order by id")
        void given_noSort_when_listed_should_orderById() {
            assertThat(ids("/v1/books?pageSize=3")).containsExactly("01", "02", "03");
        }

        @Test
        @DisplayName("GIVEN a page size over the ceiling WHEN listed SHOULD be clamped to the maximum")
        void given_hugePageSize_when_listed_should_clamp() {
            assertThat(ids("/v1/books?pageSize=100000")).hasSize(10);
        }

        @Test
        @DisplayName("GIVEN a sort key outside the allow-list WHEN listed SHOULD answer 400, not reach the database")
        void given_unsortableField_when_listed_should_be400() {
            client().get().uri("/v1/books?sort=password:asc").exchange().expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("GIVEN a forged page token WHEN listed SHOULD answer 400")
        void given_forgedToken_when_listed_should_be400() {
            client().get().uri("/v1/books?pageToken=forged").exchange().expectStatus().isBadRequest();
        }
    }

    // Each mode runs its own backend: Spring caches contexts, so the first application is still
    // listening when the second starts and both on :9090 would be a bind conflict.
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "sample.backend-port=19190",
                "sample.paging=offset",
                "spring.datasource.url=jdbc:h2:mem:offset;DB_CLOSE_DELAY=-1",
                "shiguchi.services[0].name=library",
                "shiguchi.services[0].mode=STATIC",
                "shiguchi.services[0].host=localhost",
                "shiguchi.services[0].port=19190",
                "shiguchi.services[0].static-service-classes[0]="
                        + "dev.appiaworks.shiguchi.sample.jpabackend.proto.BookServiceGrpc"
            })
    @DisplayName("offset paging")
    class Offset extends Contract {}

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "sample.backend-port=19191",
                "sample.paging=keyset",
                "spring.datasource.url=jdbc:h2:mem:keyset;DB_CLOSE_DELAY=-1",
                "shiguchi.services[0].name=library",
                "shiguchi.services[0].mode=STATIC",
                "shiguchi.services[0].host=localhost",
                "shiguchi.services[0].port=19191",
                "shiguchi.services[0].static-service-classes[0]="
                        + "dev.appiaworks.shiguchi.sample.jpabackend.proto.BookServiceGrpc"
            })
    @DisplayName("keyset paging")
    class Keyset extends Contract {}
}
