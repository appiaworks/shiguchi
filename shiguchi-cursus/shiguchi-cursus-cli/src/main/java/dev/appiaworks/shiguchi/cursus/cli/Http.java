package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** The JDK HTTP client with the gateway's auth, error mapping and one token refresh on a 401. */
final class Http {

    static final ObjectMapper JSON = new ObjectMapper();

    private final Settings settings;
    private final HttpClient client;

    Http(Settings settings) {
        this.settings = settings;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (settings.insecure()) {
            builder.sslContext(trustAll());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(parameters);
        }
        this.client = builder.build();
    }

    HttpClient client() {
        return client;
    }

    Settings settings() {
        return settings;
    }

    /** {@code application/x-www-form-urlencoded} encoding of one value. */
    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The query string of the non-blank parameters; keys may repeat. */
    static String query(List<Map.Entry<String, String>> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> p : params) {
            if (p.getValue() == null || p.getValue().isBlank()) continue;
            sb.append(sb.length() == 0 ? '?' : '&')
                    .append(enc(p.getKey()))
                    .append('=')
                    .append(enc(p.getValue()));
        }
        return sb.toString();
    }

    /** GET a JSON document from the actuator endpoint. */
    JsonNode getAdmin(String path) {
        return json(send(builder(settings.adminBase() + path, true).GET(), true, HttpResponse.BodyHandlers.ofString()));
    }

    /** POST a JSON body to the actuator endpoint; answers the parsed JSON response. */
    JsonNode postAdmin(String path, JsonNode body) {
        return postJson(settings.adminBase() + path, body, true);
    }

    /** DELETE on the actuator endpoint; answers the parsed JSON response (empty object for no body). */
    JsonNode deleteAdmin(String path) {
        return json(
                send(builder(settings.adminBase() + path, true).DELETE(), true, HttpResponse.BodyHandlers.ofString()));
    }

    /** POST a JSON body to the push path; answers the parsed JSON response. */
    JsonNode postPush(String path, JsonNode body) {
        return postJson(settings.pushBase() + path, body, false);
    }

    JsonNode postJson(String uri, JsonNode body, boolean admin) {
        HttpRequest.Builder b = builder(uri, admin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return json(send(b, admin, HttpResponse.BodyHandlers.ofString()));
    }

    /** Opens a streaming GET on the push path. The body is closed by the caller. */
    HttpResponse<InputStream> stream(String uri, Map<String, String> headers, boolean admin) {
        HttpRequest.Builder b = builder(uri, admin).GET();
        headers.forEach(b::header);
        return sendOnce(b, admin, HttpResponse.BodyHandlers.ofInputStream(), false);
    }

    HttpRequest.Builder builder(String uri, boolean admin) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).header("Accept", "application/json");
        String auth = authorization(admin);
        if (auth != null) b.header("Authorization", auth);
        return b;
    }

    /** The {@code Authorization} header value for the next request, or null. */
    String authorization(boolean admin) {
        if (admin && settings.basic() != null) {
            return "Basic "
                    + Base64.getEncoder().encodeToString(settings.basic().getBytes(StandardCharsets.UTF_8));
        }
        String token = settings.token().current();
        return token == null ? null : "Bearer " + token;
    }

    <T> HttpResponse<T> send(HttpRequest.Builder request, boolean admin, HttpResponse.BodyHandler<T> handler) {
        try {
            return sendOnce(request, admin, handler, true);
        } catch (CliException e) {
            if (!e.unauthorized() || !settings.token().canRefresh() || (admin && settings.basic() != null)) throw e;
            settings.token().refresh();
            HttpRequest.Builder retry = HttpRequest.newBuilder(request.build().uri())
                    .method(
                            request.build().method(),
                            request.build().bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            request.build().headers().map().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("Authorization")) v.forEach(x -> retry.header(k, x));
            });
            retry.header("Authorization", "Bearer " + settings.token().current());
            return sendOnce(retry, admin, handler, true);
        }
    }

    private <T> HttpResponse<T> sendOnce(
            HttpRequest.Builder request, boolean admin, HttpResponse.BodyHandler<T> handler, boolean readBodyOnError) {
        HttpResponse<T> response;
        try {
            response = client.send(request.build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(CliException.INTERRUPTED, "interrupted");
        } catch (ConnectException e) {
            throw new CliException(CliException.TRANSPORT, "cannot connect: " + describe(e), true, false);
        } catch (IOException e) {
            throw new CliException(CliException.TRANSPORT, "request failed: " + describe(e), true, false);
        }
        if (response.statusCode() >= 400) {
            String detail = null;
            Object body = response.body();
            try {
                if (body instanceof String s) detail = s;
                else if (body instanceof InputStream in) {
                    byte[] bytes = in.readNBytes(2048);
                    in.close();
                    detail = new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // the status is what matters
            }
            throw CliException.forStatus(response.statusCode(), shorten(detail));
        }
        return response;
    }

    private static JsonNode json(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new CliException(CliException.TRANSPORT, "the gateway answered something that is not JSON");
        }
    }

    static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static String shorten(String s) {
        if (s == null) return null;
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }

    private static SSLContext trustAll() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    null,
                    new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    },
                    new java.security.SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new CliException(CliException.TRANSPORT, "cannot set up --insecure TLS: " + e.getMessage());
        }
    }
}
