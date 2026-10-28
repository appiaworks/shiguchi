package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for SchemaRegistryClient, exercised against {@link StubSchemaRegistry}.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>{@code register} caches by subject+schema, so a repeat call makes no HTTP request
 *   <li>{@code loadAllVersions} fetches every version, and an unknown subject returns empty
 *       rather than throwing
 *   <li>{@code loadById} fetches and caches, and {@code cachedById} never calls the registry
 *   <li>Basic auth is sent from the URL's user info, and a trailing slash / malformed URL are
 *       handled per the constructor's contract
 * </ul>
 */
@DisplayName("SchemaRegistryClient tests")
class SchemaRegistryClientTest {

    private StubSchemaRegistry stub;

    @BeforeEach
    void startStub() {
        stub = new StubSchemaRegistry();
    }

    @AfterEach
    void stopStub() {
        stub.dispose();
    }

    private Schema schema(String name) {
        return SchemaBuilder.record(name)
                .namespace("com.acme.test")
                .fields()
                .name("id")
                .type()
                .stringType()
                .noDefault()
                .endRecord();
    }

    @Test
    @DisplayName(
            "GIVEN a new schema WHEN register called twice with the same schema SHOULD cache the id and make no second HTTP call")
    void given_sameSchemaRegisteredTwice_when_register_should_cacheAndMakeNoSecondCall() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        Schema schema = schema("Widget");

        // Act
        int firstId = client.register("widgets-value", schema);
        int requestsAfterFirst = stub.receivedRequests().size();
        int secondId = client.register("widgets-value", schema);

        // Assert
        assertThat(secondId).isEqualTo(firstId);
        assertThat(stub.receivedRequests()).hasSize(requestsAfterFirst);
    }

    @Test
    @DisplayName(
            "GIVEN a subject with several versions WHEN loadAllVersions called SHOULD fetch every version into the cache")
    void given_subjectWithSeveralVersions_when_loadAllVersions_should_fetchEveryVersion() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        Schema v1 = schema("Widget");
        Schema v2 = SchemaBuilder.record("Widget")
                .namespace("com.acme.test")
                .fields()
                .name("id")
                .type()
                .stringType()
                .noDefault()
                .name("note")
                .type()
                .stringType()
                .stringDefault("n/a")
                .endRecord();
        int id1 = stub.seedVersion("widgets-value", v1.toString());
        int id2 = stub.seedVersion("widgets-value", v2.toString());

        // Act
        List<Integer> ids = client.loadAllVersions("widgets-value");

        // Assert
        assertThat(ids).containsExactlyInAnyOrder(id1, id2);
        assertThat(client.cachedById(id1)).isPresent();
        assertThat(client.cachedById(id2)).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN a subject the registry has never seen WHEN loadAllVersions called SHOULD return an empty list without throwing")
    void given_unknownSubject_when_loadAllVersions_should_returnEmptyListWithoutThrowing() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());

        // Act
        List<Integer> ids = client.loadAllVersions("never-registered-value");

        // Assert
        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a schema id known to the registry WHEN loadById called SHOULD fetch and cache the schema")
    void given_knownId_when_loadById_should_fetchAndCacheSchema() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        Schema schema = schema("Widget");
        int id = stub.seedVersion("widgets-value", schema.toString());

        // Act
        Schema loaded = client.loadById(id);

        // Assert
        assertThat(loaded.getFullName()).isEqualTo(schema.getFullName());
        assertThat(client.cachedById(id)).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN an id nobody has fetched or registered WHEN cachedById called SHOULD return empty without calling the registry")
    void given_neverSeenId_when_cachedById_should_returnEmptyWithoutCallingRegistry() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        int requestsBefore = stub.receivedRequests().size();

        // Act
        Optional<Schema> result = client.cachedById(999);

        // Assert
        assertThat(result).isEmpty();
        assertThat(stub.receivedRequests()).hasSize(requestsBefore);
    }

    @Test
    @DisplayName(
            "GIVEN a URL with user:pass@ credentials WHEN a call is made SHOULD send an Authorization: Basic header")
    void given_urlWithCredentials_when_callMade_should_sendBasicAuthHeader() {
        // Arrange
        String urlWithAuth = "http://alice:s3cret@localhost:" + stub.port();
        SchemaRegistryClient client = new SchemaRegistryClient(urlWithAuth);

        // Act
        client.register("secured-value", schema("Secured"));

        // Assert
        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("alice:s3cret".getBytes());
        assertThat(stub.receivedAuthHeaders()).contains(expected);
    }

    @Test
    @DisplayName("GIVEN a base URL with a trailing slash WHEN a call is made SHOULD still reach the correct path")
    void given_trailingSlashInUrl_when_callMade_should_reachCorrectPath() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url() + "/");

        // Act
        int id = client.register("trailing-slash-value", schema("Widget"));

        // Assert
        assertThat(id).isPositive();
        assertThat(stub.receivedRequests()).anyMatch(r -> r.equals("POST /subjects/trailing-slash-value/versions"));
    }

    @Test
    @DisplayName("GIVEN a malformed URL WHEN the client is constructed SHOULD throw IllegalArgumentException")
    void given_malformedUrl_when_constructed_should_throwIllegalArgumentException() {
        // Arrange
        String malformed = "http://bad url with spaces";

        // Act & Assert
        assertThatThrownBy(() -> new SchemaRegistryClient(malformed, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
