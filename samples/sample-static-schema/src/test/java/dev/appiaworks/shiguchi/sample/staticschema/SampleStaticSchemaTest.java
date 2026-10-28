package dev.appiaworks.shiguchi.sample.staticschema;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Both schema sources, against the same backend, asserting the same result.
 *
 * <p>That equivalence is the sample's claim: a backend with no reflection can still be fronted,
 * and it does not matter to the caller which of the two ways the gateway learned about it. A test
 * that only checked one would not be testing the claim.
 */
@DisplayName("Static schema sample")
class SampleStaticSchemaTest {

    /** The assertions, shared by both profiles. */
    abstract static class Contract {

        @LocalServerPort
        int port;

        WebTestClient client() {
            return WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();
        }

        /** The mode this profile is expected to be running in, as gateway-routes reports it. */
        abstract String expectedMode();

        @Test
        @DisplayName("GIVEN a backend without reflection WHEN a transcoded route is called SHOULD answer from the schema the gateway loaded another way")
        void given_noReflection_when_calling_should_stillTranscode() {
            client().get()
                    .uri("/v1/products/B200")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.sku")
                    .isEqualTo("B200")
                    .jsonPath("$.name")
                    .isEqualTo("Bird seed, 10 kg");
        }

        @Test
        @DisplayName("GIVEN the configured mode WHEN the routes endpoint is read SHOULD report that mode with the same two routes either way")
        void given_mode_when_readingRoutes_should_reportTheSameRoutes() {
            client().get()
                    .uri("/actuator/gateway-routes")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.loaded")
                    .isEqualTo(1)
                    .jsonPath("$.routeCount")
                    .isEqualTo(2)
                    .jsonPath("$.services[0].mode")
                    .isEqualTo(expectedMode())
                    // Sorted by method name, so GetProduct precedes ListProducts.
                    .jsonPath("$.services[0].routes[0].http")
                    .isEqualTo("GET /v1/products/{sku}")
                    .jsonPath("$.services[0].routes[1].http")
                    .isEqualTo("GET /v1/products");
        }

        @Test
        @DisplayName("GIVEN the http annotations came from the schema file WHEN the OpenAPI document is generated SHOULD describe the same routes")
        void given_loadedSchema_when_generatingOpenApi_should_describeTheRoutes() {
            client().get()
                    .uri("/v3/api-docs")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.paths['/v1/products']")
                    .exists()
                    .jsonPath("$.paths['/v1/products/{sku}']")
                    .exists();
        }
    }

    // Each profile gets its own backend port. Spring caches contexts, so the first profile's
    // application (and its in-process backend) is still running when the second starts, and both
    // on :9090 would be a bind conflict rather than a real failure.
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "sample.backend-port=19090",
                "shiguchi.services[0].name=catalog",
                "shiguchi.services[0].mode=STATIC",
                "shiguchi.services[0].host=localhost",
                "shiguchi.services[0].port=19090",
                "shiguchi.services[0].static-service-classes[0]="
                        + "dev.appiaworks.shiguchi.sample.staticschema.proto.CatalogServiceGrpc"
            })
    @DisplayName("STATIC — schema read from the generated stubs on the classpath")
    class Static extends Contract {
        @Override
        String expectedMode() {
            return "STATIC";
        }
    }

    /** Only PB_FILE carries the proto's comments, so this assertion belongs to that profile alone. */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "sample.backend-port=19091",
                "shiguchi.services[0].name=catalog",
                "shiguchi.services[0].mode=PB_FILE",
                "shiguchi.services[0].host=localhost",
                "shiguchi.services[0].port=19091",
                "shiguchi.services[0].pb-descriptor-path=build/descriptors/catalog.pb"
            })
    @ActiveProfiles("pb")
    @DisplayName("PB_FILE — schema read from a protoc-generated FileDescriptorSet")
    class PbFile extends Contract {
        @Override
        String expectedMode() {
            return "PB_FILE";
        }

        @Test
        @DisplayName(
                "GIVEN the descriptor kept the proto's comments WHEN the OpenAPI document is generated SHOULD use them as descriptions")
        void given_sourceInfo_when_generatingOpenApi_should_describeFromTheProto() {
            // protoc --include_source_info keeps the comments; reflection never sends them and
            // compiled stubs do not retain them, so this is the one mode where documentation
            // written once in the .proto reaches the generated surfaces.
            client().get()
                    .uri("/v3/api-docs")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                            .contains("Fetches one product by SKU"));
        }
    }
}
