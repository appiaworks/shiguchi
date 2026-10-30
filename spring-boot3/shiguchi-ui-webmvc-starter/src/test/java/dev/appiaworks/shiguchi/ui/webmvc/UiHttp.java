package dev.appiaworks.shiguchi.ui.webmvc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * A tiny blocking HTTP client against the test application's random port; never follows redirects.
 * A POST carries a CSRF token the way the console sends it: the {@code XSRF-TOKEN} cookie and a
 * matching {@code X-XSRF-TOKEN} header.
 */
final class UiHttp {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private final String baseUrl;

    UiHttp(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return send(builder(path, headers).GET().build());
    }

    HttpResponse<String> post(String path, String json, String... headers) throws IOException, InterruptedException {
        String token = UUID.randomUUID().toString();
        return send(builder(path, headers)
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    /** A POST without a CSRF token, as a cross-site page would send it. */
    HttpResponse<String> postWithoutCsrf(String path, String json) throws IOException, InterruptedException {
        return send(builder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The JSON body of the console's invoke request; the payload is itself a JSON string. */
    static String invokeJson(String service, String method, String payload) {
        return "{\"serviceName\":\"" + service + "\",\"methodName\":\"" + method + "\",\"payload\":\""
                + payload.replace("\"", "\\\"") + "\"}";
    }

    private HttpRequest.Builder builder(String path, String... headers) {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return b;
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
