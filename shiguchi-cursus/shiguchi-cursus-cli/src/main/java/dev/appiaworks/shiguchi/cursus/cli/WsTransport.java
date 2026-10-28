package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The {@code push.v1.json} WebSocket protocol at {@code <path>/ws}. */
final class WsTransport implements Transport {

    private final Http http;

    WsTransport(Http http) {
        this.http = http;
    }

    @Override
    public End stream(SubRequest request, EventSink sink, Lifecycle lifecycle) throws Exception {
        String base = http.settings().pushBase();
        URI uri = URI.create(
                (base.startsWith("https://") ? "wss://" : "ws://") + base.substring(base.indexOf("://") + 3) + "/ws");
        CompletableFuture<End> done = new CompletableFuture<>();
        ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cursus-ws-send");
            t.setDaemon(true);
            return t;
        });
        Listener listener = new Listener(request, sink, done, sender);
        HttpClient client = http.client();
        WebSocket.Builder builder = client.newWebSocketBuilder().subprotocols("push.v1.json");
        String auth = http.authorization(false);
        if (auth != null) builder.header("Authorization", auth);
        WebSocket socket;
        try {
            socket = builder.buildAsync(uri, listener).get();
        } catch (ExecutionException e) {
            sender.shutdownNow();
            throw translate(e.getCause());
        }
        lifecycle.onStop(() -> {
            socket.abort();
            done.complete(End.of(End.Kind.DONE, null));
        });
        try {
            return done.get();
        } catch (ExecutionException e) {
            if (lifecycle.isStopped()) return End.of(End.Kind.DONE, null);
            throw translate(e.getCause());
        } finally {
            lifecycle.onStop(null);
            sender.shutdownNow();
            socket.abort();
        }
    }

    private static Exception translate(Throwable cause) {
        if (cause instanceof WebSocketHandshakeException h) {
            HttpResponse<?> response = h.getResponse();
            return CliException.forStatus(response.statusCode(), "WebSocket handshake refused");
        }
        if (cause instanceof CliException c) return c;
        if (cause instanceof Exception e) return new IOException(Http.describe(e), e);
        return new IOException(String.valueOf(cause));
    }

    private static final class Listener implements WebSocket.Listener {

        private final SubRequest request;
        private final EventSink sink;
        private final CompletableFuture<End> done;
        private final ExecutorService sender;
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        Listener(SubRequest request, EventSink sink, CompletableFuture<End> done, ExecutorService sender) {
            this.request = request;
            this.sink = sink;
            this.done = done;
            this.sender = sender;
        }

        private void send(ObjectNode frame) {
            WebSocket target = socket;
            sender.execute(() -> {
                try {
                    target.sendText(frame.toString(), true).join();
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            if (request.session() != null || request.origin() != null) {
                ObjectNode connect = Http.JSON.createObjectNode().put("type", "connect");
                if (request.session() != null) connect.put("session", request.session());
                if (request.origin() != null) connect.put("origin", request.origin());
                send(connect);
            }
            ObjectNode sub = Http.JSON.createObjectNode().put("type", "sub");
            ArrayNode topics = sub.putArray("topics");
            request.topics().forEach(topics::add);
            if (request.afterByTopic() != null && !request.afterByTopic().isEmpty()) {
                ObjectNode after = sub.putObject("after");
                request.afterByTopic().forEach(after::put);
            } else if (request.after() != null && !request.after().isBlank()) {
                sub.put("after", request.after());
            }
            if (request.qos() != null) sub.put("qos", request.qos());
            if (request.group() != null) {
                sub.put("group", request.group());
                if (request.member() != null) sub.put("member", request.member());
            }
            send(sub);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    handle(Http.JSON.readTree(text));
                } catch (IOException e) {
                    done.completeExceptionally(e);
                }
            }
            webSocket.request(1);
            return null;
        }

        private void handle(JsonNode frame) {
            switch (frame.path("type").asText()) {
                case "event" -> {
                    ObjectNode event = Http.JSON.createObjectNode();
                    event.put("topic", frame.path("topic").asText());
                    if (frame.has("id")) event.put("id", frame.path("id").asText());
                    event.set("data", frame.has("data") ? frame.get("data") : Http.JSON.nullNode());
                    for (String optional : new String[] {"attributes", "key", "time"}) {
                        if (frame.has(optional) && !frame.get(optional).isNull())
                            event.set(optional, frame.get(optional));
                    }
                    boolean more = sink.event(event);
                    if (request.ack() && frame.has("id")) {
                        ObjectNode ack = Http.JSON.createObjectNode().put("type", "ack");
                        ack.put("topic", frame.path("topic").asText())
                                .put("id", frame.path("id").asText());
                        send(ack);
                    }
                    if (!more) done.complete(End.of(End.Kind.DONE, null));
                }
                case "overflow" -> {
                    sink.control("overflow", frame);
                    if (!"session_overflow".equals(frame.path("reason").asText())) {
                        done.complete(
                                End.of(End.Kind.OVERFLOW, frame.path("reason").asText(null)));
                    }
                }
                case "reset" -> sink.control("reset", frame);
                case "close" ->
                    done.complete(End.of(End.Kind.CLOSED, frame.path("reason").asText(null)));
                case "error" -> {
                    String message =
                            frame.path("message").asText(frame.path("error").asText(frame.toString()));
                    String lower = message.toLowerCase();
                    boolean auth =
                            lower.contains("unauthor") || lower.contains("forbidden") || lower.contains("denied");
                    done.completeExceptionally(new CliException(
                            auth ? CliException.AUTH : CliException.TRANSPORT,
                            "server error: " + message,
                            false,
                            lower.contains("unauthor") || lower.contains("expired")));
                }
                default -> {}
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode == 4401) {
                done.completeExceptionally(
                        new CliException(CliException.AUTH, "token expired (close 4401)", false, true));
            } else if (statusCode == 4440) {
                // The browser session behind the connection ended: not a token problem, so no refresh and retry.
                done.completeExceptionally(
                        new CliException(CliException.AUTH, "session ended (close 4440)", false, false));
            } else {
                done.complete(End.of(End.Kind.CLOSED, reason == null || reason.isBlank() ? null : reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            done.completeExceptionally(error);
        }
    }
}
