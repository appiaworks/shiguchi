package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET <path>/subscribe} as Server-Sent Events, resumed with {@code Last-Event-ID}. */
final class SseTransport implements Transport {

    private final Http http;

    SseTransport(Http http) {
        this.http = http;
    }

    @Override
    public End stream(SubRequest request, EventSink sink, Lifecycle lifecycle) throws IOException {
        List<Map.Entry<String, String>> params = new ArrayList<>();
        request.topics().forEach(t -> params.add(Map.entry("topic", t)));
        params.add(Map.entry("group", nz(request.group())));
        params.add(Map.entry("member", nz(request.member())));
        params.add(Map.entry("session", nz(request.session())));
        params.add(Map.entry("qos", nz(request.qos())));
        params.add(Map.entry("origin", nz(request.origin())));
        String uri = http.settings().pushBase() + "/subscribe" + Http.query(params);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/event-stream");
        if (request.after() != null && !request.after().isBlank()) headers.put("Last-Event-ID", request.after());

        HttpResponse<InputStream> response = http.stream(uri, headers, false);
        InputStream body = response.body();
        lifecycle.onStop(() -> closeQuietly(body));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String id = null;
            String name = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null || name != null) {
                        End end = dispatch(name, id, data == null ? "" : data.toString(), request, sink);
                        if (end != null) return end;
                    }
                    id = null;
                    name = null;
                    data = null;
                } else if (line.startsWith(":")) {
                    continue;
                } else {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    switch (field) {
                        case "id" -> id = value;
                        case "event" -> name = value;
                        case "data" -> {
                            if (data == null) data = new StringBuilder();
                            else data.append('\n');
                            data.append(value);
                        }
                        default -> {}
                    }
                }
            }
            return lifecycle.isStopped() ? End.of(End.Kind.DONE, null) : End.of(End.Kind.EOF, null);
        } catch (IOException e) {
            if (lifecycle.isStopped()) return End.of(End.Kind.DONE, null);
            throw e;
        } finally {
            lifecycle.onStop(null);
        }
    }

    private End dispatch(String name, String id, String data, SubRequest request, EventSink sink) {
        JsonNode payload = parse(data);
        if ("close".equals(name)) {
            return End.of(End.Kind.CLOSED, payload.path("reason").asText(null));
        }
        if ("overflow".equals(name)) {
            String reason = payload.path("reason").asText(null);
            sink.control("overflow", payload);
            // session_overflow precedes the events and the stream continues; queue_full ends it
            return "session_overflow".equals(reason) ? null : End.of(End.Kind.OVERFLOW, reason);
        }
        if ("reset".equals(name)) {
            // The gateway says events were missed; the retained ones follow on this stream.
            sink.control("reset", payload);
            return null;
        }
        if (name == null) name = "";
        ObjectNode event = Http.JSON.createObjectNode();
        event.put("topic", name);
        if (id != null) event.put("id", id);
        event.set("data", payload);
        boolean more = sink.event(event);
        if (request.ack() && id != null) ack(request, name, id);
        return more ? null : End.of(End.Kind.DONE, null);
    }

    private void ack(SubRequest request, String topic, String id) {
        ObjectNode body = Http.JSON.createObjectNode();
        body.put("topic", topic).put("id", id);
        if (request.group() != null) body.put("group", request.group()).put("member", request.member());
        else if (request.session() != null) body.put("session", request.session());
        http.postPush("/ack", body);
    }

    static JsonNode parse(String data) {
        if (data == null || data.isEmpty()) return Http.JSON.createObjectNode();
        try {
            return Http.JSON.readTree(data);
        } catch (IOException e) {
            return Http.JSON.getNodeFactory().textNode(data);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
