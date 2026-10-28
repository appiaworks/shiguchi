package dev.appiaworks.shiguchi.avro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The slice of the Confluent Schema Registry REST API the gateway needs, over {@code WebClient},
 * with the one design rule that matters: <b>every call happens at load time</b>. Schemas are
 * registered and their ids fetched when a service loads or the application becomes ready; a
 * request only reads the caches, so no Netty thread ever blocks on the registry. An id the caches
 * have never seen is an error, not a lookup.
 *
 * <p>Endpoints used: {@code POST /subjects/{subject}/versions} (register, idempotent for an
 * identical schema), {@code GET /subjects/{subject}/versions} and {@code
 * GET /subjects/{subject}/versions/{v}} (every version's id and schema, so evolved writers
 * decode), {@code GET /schemas/ids/{id}}. HTTP Basic credentials come from the URL's user info.
 */
public class SchemaRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient client;
    private final Duration timeout;
    private final Map<Integer, Schema> byId = new ConcurrentHashMap<>();
    private final Map<String, Integer> idBySubjectAndFingerprint = new ConcurrentHashMap<>();

    public SchemaRegistryClient(String baseUrl, Duration timeout, WebClient.Builder builder) {
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("schema-registry-url is not a URI: " + baseUrl, e);
        }
        String userInfo = uri.getUserInfo();
        String base = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + (uri.getRawPath() != null ? uri.getRawPath().replaceAll("/+$", "") : "");
        WebClient.Builder b = (builder != null ? builder.clone() : WebClient.builder()).baseUrl(base);
        if (userInfo != null && !userInfo.isEmpty()) {
            String basic = Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        }
        this.client = b.build();
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(10);
    }

    public SchemaRegistryClient(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, null);
    }

    public SchemaRegistryClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(10), null);
    }

    /**
     * Registers {@code schema} under {@code subject} (a no-op returning the existing id when the
     * registry already holds an identical schema) and caches the id. Load time only.
     */
    public int register(String subject, Schema schema) {
        String key = subject + "#" + AvroRegistryIds.fingerprintHex(schema);
        Integer cached = idBySubjectAndFingerprint.get(key);
        if (cached != null) return cached;
        ObjectNode body = JSON.createObjectNode();
        body.put("schema", schema.toString());
        body.put("schemaType", "AVRO");
        JsonNode response = call("POST", "/subjects/" + encode(subject) + "/versions", body);
        int id = response.path("id").asInt(-1);
        if (id < 0) throw new IllegalStateException("registry answered without an id for subject " + subject);
        idBySubjectAndFingerprint.put(key, id);
        byId.put(id, schema);
        return id;
    }

    /** Fetches every version of {@code subject} into the id cache, so evolved writers can be decoded. */
    public List<Integer> loadAllVersions(String subject) {
        JsonNode versions;
        try {
            versions = call("GET", "/subjects/" + encode(subject) + "/versions", null);
        } catch (WebClientResponseException.NotFound e) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonNode v : versions) {
            JsonNode version = call("GET", "/subjects/" + encode(subject) + "/versions/" + v.asInt(), null);
            int id = version.path("id").asInt(-1);
            String text = version.path("schema").asText(null);
            if (id < 0 || text == null) continue;
            byId.computeIfAbsent(id, k -> new Schema.Parser().parse(text));
            ids.add(id);
        }
        return ids;
    }

    /** Fetches one schema by id into the cache. Load time only. */
    public Schema loadById(int id) {
        Schema cached = byId.get(id);
        if (cached != null) return cached;
        JsonNode response = call("GET", "/schemas/ids/" + id, null);
        String text = response.path("schema").asText(null);
        if (text == null) throw new IllegalStateException("registry has no schema for id " + id);
        Schema schema = new Schema.Parser().parse(text);
        byId.put(id, schema);
        return schema;
    }

    /** A schema already fetched or registered, by registry id. Request time: cache only. */
    public Optional<Schema> cachedById(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The id a schema was registered under for {@code subject}, if this client registered it. */
    public Optional<Integer> cachedId(String subject, Schema schema) {
        return Optional.ofNullable(
                idBySubjectAndFingerprint.get(subject + "#" + AvroRegistryIds.fingerprintHex(schema)));
    }

    private JsonNode call(String method, String path, ObjectNode body) {
        WebClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .accept(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"));
        WebClient.RequestHeadersSpec<?> request = body != null
                ? spec.contentType(MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
                        .bodyValue(body.toString())
                : spec;
        String text;
        try {
            text = request.retrieve().bodyToMono(String.class).block(timeout);
        } catch (WebClientResponseException e) {
            log.debug("registry {} {} -> {}", method, path, e.getStatusCode());
            throw e;
        }
        try {
            return text == null ? JSON.nullNode() : JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("registry answered non-JSON to " + method + " " + path, e);
        }
    }

    private static String encode(String subject) {
        return java.net.URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
