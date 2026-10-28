package dev.appiaworks.shiguchi.cursus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.lang.Nullable;

/**
 * Actuator endpoint for the push surface.
 *
 * <p>Reads: {@code GET /actuator/gateway-push} (the overview), {@code .../namespaces},
 * {@code .../topics?q=}, {@code .../connections?principal=&topic=&transport=},
 * {@code .../messages?topic=&from=&to=&after=&before=&attr=k:v,k2:v2&type=&q=&limit=} (the
 * archive), {@code .../messages/{id}?topic=}, {@code .../messages/{id}/deliveries?topic=}
 * (delivered and dropped counts, and the per-connection log for a namespace with
 * {@code delivery-log: true}), {@code .../jobs}, {@code .../jobs/{id}}, {@code .../audit?limit=},
 * {@code .../sessions?q=<prefix>&limit=} (persistent sessions' cursors; {@code DELETE .../sessions/{session}} forgets one),
 * {@code .../groups?q=<prefix>&limit=} (consumer groups: pending claims, members, position; {@code DELETE .../groups/{group}}),
 * {@code .../sources}, {@code .../dlq?q=<source>&limit=}, {@code .../dlq/{id}} (the dead-letter queue;
 * {@code DELETE} drops an entry, {@code POST .../dlq/{id}/retry {"topic"?}} publishes it again).
 * The NDJSON export of a query is {@code GET <cursus.path>/export}, on the push path, since this
 * layer collects a stream into one body. A message is addressed by its id with the topic as a parameter: a
 * topic name may contain {@code /}, which a path segment cannot carry.
 *
 * <p>Writes: {@code DELETE .../connections/{id}} ends a connection; {@code POST .../publish}
 * with {@code {"topic": "...", "data": "<json as a string>"}} publishes a test event as the
 * operator; {@code POST .../messages/{id}/republish} with {@code {"topic", "target"?, "reason"?,
 * "dryRun"?}} republishes one archived event; {@code POST .../messages/republish} with the search
 * parameters plus {@code target}, {@code reason}, {@code dryRun} republishes a query's worth as a
 * job; {@code DELETE .../messages/{id}?topic=} tombstones one; {@code DELETE .../jobs/{id}}
 * cancels a job; {@code POST .../groups/{group}/seek} and {@code POST .../sessions/{session}/seek}
 * with {@code {"topic", "to"}} ({@code to}: an id, {@code first} or {@code head}) move a group's
 * position or a session's cursor. Exposed only when {@code management.endpoints.web.exposure.include} names
 * {@code gateway-push}. The writes need an authenticated caller (the gateway's security chain
 * gates them) and, when {@code shiguchi.cursus.admin.role} is set, that role; every write is
 * recorded in the audit log.
 */
@Endpoint(id = "gateway-push")
public class PushEndpoint {

    private static final Duration BLOCK = Duration.ofSeconds(30);
    /** A send may wait up to its own timeout (at most a minute) for an acknowledgement. */
    private static final Duration SEND_BLOCK = Duration.ofSeconds(70);

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final PushReport report;
    private final PushProperties properties;

    public PushEndpoint(PushReport report, PushProperties properties) {
        this.report = report;
        this.properties = properties;
    }

    @ReadOperation
    public Map<String, Object> overview() {
        return report.overview();
    }

    @ReadOperation
    public WebEndpointResponse<Object> section(
            @Selector String section,
            @Nullable String q,
            @Nullable String principal,
            @Nullable String topic,
            @Nullable String transport,
            @Nullable String from,
            @Nullable String to,
            @Nullable String after,
            @Nullable String before,
            @Nullable String attr,
            @Nullable String type,
            @Nullable String limit) {
        try {
            return switch (section) {
                case "namespaces" -> new WebEndpointResponse<>(Map.of("namespaces", report.namespaces()));
                case "topics" -> new WebEndpointResponse<>(Map.of("topics", report.topics(q, 500)));
                case "connections" ->
                    new WebEndpointResponse<>(Map.of("connections", report.connections(principal, topic, transport)));
                case "messages" -> {
                    WebEndpointResponse<Object> noArchive = noArchive();
                    if (noArchive != null) yield noArchive;
                    PushArchive.Query query = query(topic, from, to, after, before, attr, type, q, limit);
                    yield new WebEndpointResponse<>(Map.of("messages", report.messages(query)));
                }
                case "jobs" -> new WebEndpointResponse<>(Map.of("jobs", report.jobs()));
                case "audit" -> new WebEndpointResponse<>(Map.of("audit", report.auditLog(limit(limit))));
                case "sources" -> new WebEndpointResponse<>(Map.of("sources", report.sources()));
                case "dlq" -> new WebEndpointResponse<>(Map.of("dlq", report.dlq(q, limit(limit))));
                case "sessions" -> new WebEndpointResponse<>(Map.of("sessions", report.sessions(q, limit(limit))));
                case "groups" -> new WebEndpointResponse<>(Map.of("groups", report.groups(q, limit(limit))));
                default -> notFound("no section '" + section + "'; one of " + sections());
            };
        } catch (PushException e) {
            return error(e);
        }
    }

    /** {@code jobs/{id}} and {@code messages/{id}?topic=}. */
    @ReadOperation
    public WebEndpointResponse<Object> item(@Selector String section, @Selector String id, @Nullable String topic) {
        switch (section) {
            case "jobs" -> {
                Map<String, Object> job = report.job(id);
                return job == null ? notFound("no job '" + id + "' on this instance") : new WebEndpointResponse<>(job);
            }
            case "dlq" -> {
                Map<String, Object> entry = report.dlqEntry(id);
                return entry == null
                        ? notFound("no dead-lettered entry '" + id + "' on this instance")
                        : new WebEndpointResponse<>(entry);
            }
            case "messages" -> {
                WebEndpointResponse<Object> noArchive = noArchive();
                if (noArchive != null) return noArchive;
                if (topic == null || topic.isBlank()) return error(PushException.badRequest("topic is required"));
                try {
                    Map<String, Object> found = report.message(topic, id);
                    return found == null
                            ? notFound("no archived event " + id + " on " + topic)
                            : new WebEndpointResponse<>(found);
                } catch (IllegalArgumentException e) {
                    return error(PushException.badRequest(e.getMessage()));
                } catch (PushException e) {
                    return error(e);
                }
            }
            default -> {
                return notFound("no '" + section + "/{id}'; one of jobs/{id}, messages/{id}?topic=, dlq/{id}");
            }
        }
    }

    /** {@code messages/{id}/deliveries?topic=&limit=}: what happened to one event on its way to the subscribers. */
    @ReadOperation
    public WebEndpointResponse<Object> deliveries(
            @Selector String section,
            @Selector String id,
            @Selector String action,
            @Nullable String topic,
            @Nullable String limit) {
        if (!"messages".equals(section) || !"deliveries".equals(action)) {
            return notFound("GET with three segments applies to messages/{id}/deliveries?topic=");
        }
        if (topic == null || topic.isBlank()) return error(PushException.badRequest("topic is required"));
        try {
            return new WebEndpointResponse<>(
                    report.deliveries(topic, id, limit(limit)).block(BLOCK));
        } catch (PushException e) {
            return error(e);
        }
    }

    /** {@code POST groups/{group}/seek} and {@code POST sessions/{key}/seek}, body {@code {topic, to}} ({@code to}: an id, {@code first} or {@code head}). */
    private WebEndpointResponse<Object> seek(String section, String id, String topic, String to, SecurityContext ctx) {
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        String by = principal(ctx);
        boolean group = "groups".equals(section);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(group ? "group" : "session", id);
        arguments.put("topic", topic);
        arguments.put("to", to);
        try {
            Boolean done = (group ? report.seekGroup(id, topic, to) : report.seekSession(id, topic, to)).block(BLOCK);
            boolean ok = Boolean.TRUE.equals(done);
            report.audit().record(by, "seek_" + (group ? "group" : "session"), arguments, ok ? "ok" : "not_found");
            return ok
                    ? new WebEndpointResponse<>(Map.of("seeked", true, "topic", topic, "to", to))
                    : notFound("no " + (group ? "group '" : "session '") + id + "' on " + topic);
        } catch (PushException e) {
            report.audit().record(by, "seek_" + (group ? "group" : "session"), arguments, e.code());
            return error(e);
        }
    }

    /** {@code POST dlq/{id}/retry {"topic"?}}: publish a dead-lettered record again. */
    private WebEndpointResponse<Object> retryDlq(String id, String topic, SecurityContext ctx) {
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        String by = principal(ctx);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", id);
        arguments.put("topic", topic);
        try {
            PushEvent e = report.retryDlq(id, topic, by).block(BLOCK);
            if (e == null) {
                report.audit().record(by, "dlq_retry", arguments, "not_found");
                return notFound("no dead-lettered entry '" + id + "' on this instance");
            }
            report.audit().record(by, "dlq_retry", arguments, "ok " + e.id());
            return new WebEndpointResponse<>(Map.of("id", id, "topic", e.topic().toString(), "eventId", e.id()));
        } catch (PushException e) {
            report.audit().record(by, "dlq_retry", arguments, e.code());
            return error(e);
        }
    }

    /** {@code DELETE connections/{id}}, {@code DELETE jobs/{id}} and {@code DELETE messages/{id}?topic=} (the tombstone). */
    @DeleteOperation
    public WebEndpointResponse<Object> delete(
            @Selector String section, @Selector String id, @Nullable String topic, SecurityContext ctx) {
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        String by = principal(ctx);
        switch (section) {
            case "connections" -> {
                boolean done = report.disconnect(id);
                String remote = done ? null : report.remoteInstance(id);
                if (remote != null) {
                    report.audit().record(by, "disconnect", Map.of("id", id), "remote " + remote);
                    return new WebEndpointResponse<>(
                            Map.of(
                                    "error",
                                    "conflict",
                                    "message",
                                    "connection '" + id + "' lives on instance " + remote,
                                    "instance",
                                    remote),
                            409);
                }
                report.audit().record(by, "disconnect", Map.of("id", id), done ? "ok" : "not_found");
                return done
                        ? new WebEndpointResponse<>(Map.of("id", id, "disconnected", true))
                        : notFound("no connection '" + id + "' on this instance");
            }
            case "groups" -> {
                long gone = report.dropGroup(id);
                report.audit().record(by, "drop_group", Map.of("group", id), gone > 0 ? "ok " + gone : "not_found");
                return gone > 0
                        ? new WebEndpointResponse<>(Map.of("group", id, "dropped", gone))
                        : notFound("no group '" + id + "'");
            }
            case "sessions" -> {
                long gone = report.dropSession(id);
                report.audit().record(by, "drop_session", Map.of("session", id), gone > 0 ? "ok " + gone : "not_found");
                return gone > 0
                        ? new WebEndpointResponse<>(Map.of("session", id, "dropped", gone))
                        : notFound("no session '" + id + "'");
            }
            case "dlq" -> {
                boolean done = report.deleteDlq(id);
                report.audit().record(by, "dlq_delete", Map.of("id", id), done ? "ok" : "not_found");
                return done
                        ? new WebEndpointResponse<>(Map.of("id", id, "deleted", true))
                        : notFound("no dead-lettered entry '" + id + "' on this instance");
            }
            case "jobs" -> {
                boolean done = report.cancelJob(id);
                report.audit().record(by, "cancel_job", Map.of("id", id), done ? "ok" : "not_running");
                return done
                        ? new WebEndpointResponse<>(Map.of("id", id, "cancelled", true))
                        : notFound("no running job '" + id + "' on this instance");
            }
            case "messages" -> {
                WebEndpointResponse<Object> noArchive = noArchive();
                if (noArchive != null) return noArchive;
                if (topic == null || topic.isBlank()) return error(PushException.badRequest("topic is required"));
                Map<String, Object> arguments = Map.of("topic", topic, "id", id);
                try {
                    Boolean done = report.tombstone(topic, id, by).block(BLOCK);
                    boolean ok = Boolean.TRUE.equals(done);
                    report.audit().record(by, "tombstone", arguments, ok ? "ok" : "not_found");
                    return ok
                            ? new WebEndpointResponse<>(Map.of("topic", topic, "id", id, "tombstoned", true))
                            : notFound("no archived event " + id + " on " + topic + " to tombstone");
                } catch (PushException e) {
                    report.audit().record(by, "tombstone", arguments, e.code());
                    return error(e);
                }
            }
            default -> {
                return notFound(
                        "DELETE applies to connections/{id}, jobs/{id}, dlq/{id}, sessions/{session}, groups/{group} and messages/{id}?topic=");
            }
        }
    }

    /** {@code POST connections/{id}/send}: an event to one connection, wherever it lives, as the operator. */
    private WebEndpointResponse<Object> sendToConnection(
            String id, String data, boolean requireAck, Long timeoutMs, SecurityContext ctx) {
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        if (data == null || data.isBlank()) return error(PushException.badRequest("data is required"));
        String by = principal(ctx);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", id);
        arguments.put("bytes", data.length());
        arguments.put("requireAck", requireAck);
        try {
            Map<String, Object> out =
                    report.sendToConnection(id, data, requireAck, timeoutMs).block(SEND_BLOCK);
            report.audit().record(by, "send", arguments, "ok " + out.get("id"));
            return new WebEndpointResponse<>(out);
        } catch (PushException e) {
            report.audit().record(by, "send", arguments, e.code());
            return error(e);
        }
    }

    /** {@code POST publish} without an origin. */
    public WebEndpointResponse<Object> publish(
            @Selector String section, String topic, String data, SecurityContext ctx) {
        return publish(section, topic, data, null, ctx);
    }

    /** {@code POST publish}; {@code origin} (optional) is stored as the event's {@code push.origin}. */
    @WriteOperation
    public WebEndpointResponse<Object> publish(
            @Selector String section, String topic, String data, @Nullable String origin, SecurityContext ctx) {
        if (!"publish".equals(section)) {
            return notFound("POST applies to publish, messages/republish and messages/{id}/republish");
        }
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        if (topic == null || data == null) {
            return new WebEndpointResponse<>(
                    Map.of("error", "bad_request", "message", "topic and data are required"), 400);
        }
        String by = principal(ctx);
        try {
            PushEvent e = report.publish(topic, data.getBytes(StandardCharsets.UTF_8), Map.of(), origin)
                    .block(BLOCK);
            report.audit().record(by, "publish", Map.of("topic", topic, "bytes", data.length()), "ok " + e.id());
            return new WebEndpointResponse<>(Map.of("topic", e.topic().toString(), "id", e.id()));
        } catch (PushException e) {
            report.audit().record(by, "publish", Map.of("topic", topic), e.code());
            return error(e);
        }
    }

    /** {@code POST messages/republish}: a query's worth, as a job (or a dry-run count). */
    @WriteOperation
    public WebEndpointResponse<Object> republishAll(
            @Selector String section,
            @Selector String action,
            @Nullable String topic,
            @Nullable String from,
            @Nullable String to,
            @Nullable String after,
            @Nullable String before,
            @Nullable String attr,
            @Nullable String type,
            @Nullable String q,
            @Nullable String target,
            @Nullable String reason,
            @Nullable Boolean dryRun,
            SecurityContext ctx) {
        if (!"messages".equals(section) || !"republish".equals(action)) {
            return notFound("POST with two segments applies to messages/republish");
        }
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        WebEndpointResponse<Object> noArchive = noArchive();
        if (noArchive != null) return noArchive;
        boolean dry = dryRun == null || dryRun;
        String by = principal(ctx);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("topic", topic);
        arguments.put("from", from);
        arguments.put("to", to);
        arguments.put("attr", attr);
        arguments.put("type", type);
        arguments.put("q", q);
        arguments.put("target", target);
        arguments.put("reason", reason);
        arguments.put("dryRun", dry);
        try {
            int max = properties.getAdmin().getRepublish().getMaxBatch();
            PushArchive.Query query = query(topic, from, to, after, before, attr, type, q, Integer.toString(max));
            Map<String, Object> out = report.republishAll(query, target, reason, by, dry);
            if (!dry) {
                Object job = out.get("job");
                String jobId = job instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : "?";
                report.audit().record(by, "republish_all", arguments, "job " + jobId);
            }
            return new WebEndpointResponse<>(out);
        } catch (PushException e) {
            if (!dry) report.audit().record(by, "republish_all", arguments, e.code());
            return error(e);
        }
    }

    /** {@code POST messages/{id}/republish}: one event, its topic in the body. */
    @WriteOperation
    public WebEndpointResponse<Object> republish(
            @Selector String section,
            @Selector String id,
            @Selector String action,
            @Nullable String topic,
            @Nullable String target,
            @Nullable String reason,
            @Nullable Boolean dryRun,
            @Nullable String to,
            @Nullable String data,
            @Nullable Boolean requireAck,
            @Nullable Long timeoutMs,
            SecurityContext ctx) {
        if ("dlq".equals(section) && "retry".equals(action)) return retryDlq(id, topic, ctx);
        if ("connections".equals(section) && "send".equals(action)) {
            return sendToConnection(id, data, requireAck != null && requireAck, timeoutMs, ctx);
        }
        if (("groups".equals(section) || "sessions".equals(section)) && "seek".equals(action)) {
            return seek(section, id, topic, to, ctx);
        }
        if (!"messages".equals(section) || !"republish".equals(action)) {
            return notFound(
                    "POST with three segments applies to messages/{id}/republish, dlq/{id}/retry, connections/{id}/send,"
                            + " groups/{group}/seek and sessions/{key}/seek");
        }
        WebEndpointResponse<Object> refused = gate(ctx);
        if (refused != null) return refused;
        WebEndpointResponse<Object> noArchive = noArchive();
        if (noArchive != null) return noArchive;
        if (topic == null || topic.isBlank()) return error(PushException.badRequest("topic is required"));
        boolean dry = dryRun != null && dryRun;
        String by = principal(ctx);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("topic", topic);
        arguments.put("id", id);
        arguments.put("target", target);
        arguments.put("reason", reason);
        arguments.put("dryRun", dry);
        try {
            Map<String, Object> out =
                    report.republish(topic, id, target, reason, by, dry).block(BLOCK);
            if (!dry) report.audit().record(by, "republish", arguments, "ok " + out.get("id"));
            return new WebEndpointResponse<>(out);
        } catch (PushException e) {
            if (!dry) report.audit().record(by, "republish", arguments, e.code());
            return error(e);
        }
    }

    /** The search parameters as a query; a bad value is a 400. */
    static PushArchive.Query query(
            String topic,
            String from,
            String to,
            String after,
            String before,
            String attr,
            String type,
            String q,
            String limit) {
        return new PushArchive.Query(
                topic,
                instant("from", from),
                instant("to", to),
                after,
                before,
                attributes(attr),
                type,
                q,
                limit(limit));
    }

    static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw PushException.badRequest(name + " must be an ISO-8601 instant, got '" + value + "'");
        }
    }

    /** {@code k:v,k2:v2}; a value may itself contain colons. */
    static Map<String, String> attributes(String attr) {
        Map<String, String> out = new LinkedHashMap<>();
        if (attr == null || attr.isBlank()) return out;
        for (String pair : attr.split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) {
                throw PushException.badRequest("attr must be k:v pairs separated by commas, got '" + attr + "'");
            }
            out.put(pair.substring(0, colon).trim(), pair.substring(colon + 1).trim());
        }
        return out;
    }

    private static int limit(String limit) {
        if (limit == null || limit.isBlank()) return DEFAULT_LIMIT;
        try {
            int n = Integer.parseInt(limit.trim());
            if (n <= 0) throw PushException.badRequest("limit must be above 0");
            return Math.min(n, MAX_LIMIT);
        } catch (NumberFormatException e) {
            throw PushException.badRequest("limit must be a number, got '" + limit + "'");
        }
    }

    /** A 404 with {@code error: no_archive} when {@code archive.retention} is zero, else null. */
    private WebEndpointResponse<Object> noArchive() {
        if (report.archive().searchCapabilities().enabled()) return null;
        return new WebEndpointResponse<>(
                Map.of(
                        "error",
                        "no_archive",
                        "message",
                        "no archive: set shiguchi.cursus.archive.retention above zero (store POSTGRES or MEMORY)"),
                404);
    }

    /** The role gate for writes; null when the caller may proceed. */
    private WebEndpointResponse<Object> gate(SecurityContext ctx) {
        String role = properties.getAdmin().getRole();
        if (role == null || role.isBlank()) return null;
        if (ctx != null
                && ctx.getPrincipal() instanceof org.springframework.security.core.Authentication auth
                && PushAdminGate.holdsRole(auth, role)) {
            return null;
        }
        return new WebEndpointResponse<>(
                Map.of("error", "forbidden", "message", "role " + role + " required (shiguchi.cursus.admin.role)"),
                403);
    }

    private static String principal(SecurityContext ctx) {
        if (ctx == null || ctx.getPrincipal() == null) return "anonymous";
        return ctx.getPrincipal().getName();
    }

    private static WebEndpointResponse<Object> error(PushException e) {
        return new WebEndpointResponse<>(
                Map.of("error", e.code(), "message", e.getMessage()), e.status().value());
    }

    private static WebEndpointResponse<Object> notFound(String message) {
        return new WebEndpointResponse<>(Map.of("error", "not_found", "message", message), 404);
    }

    static List<String> sections() {
        return List.of(
                "namespaces",
                "topics",
                "connections",
                "messages",
                "jobs",
                "audit",
                "sources",
                "dlq",
                "sessions",
                "groups");
    }
}
