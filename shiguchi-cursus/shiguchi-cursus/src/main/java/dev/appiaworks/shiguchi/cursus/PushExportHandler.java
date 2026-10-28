package dev.appiaworks.shiguchi.cursus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code GET <path>/export?topic=&from=&to=&after=&before=&attr=&type=&q=&limit=}: an archive
 * query streamed as NDJSON, one event per line as the message views render it
 * ({@code admin.payload-visibility} applies), up to {@code limit} (default 10 000, at most
 * 100 000). Served on the push path rather than the actuator endpoint, whose layer collects a
 * stream into one body; gated the same way: an authenticated caller holding {@code admin.role}
 * when one is set. Recorded in the audit log with the number of lines.
 */
public final class PushExportHandler {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    static final int DEFAULT_LIMIT = 10_000;
    static final int MAX_LIMIT = 100_000;

    private final PushProperties properties;
    private final PushReport report;

    public PushExportHandler(PushProperties properties, PushReport report) {
        this.properties = properties;
        this.report = report;
    }

    public Mono<ServerResponse> export(ServerRequest request) {
        return PushSubscribeHandler.principal(request)
                .flatMap(principal -> {
                    Authentication auth = principal.orElse(null);
                    if (!TopicAuthorizer.isAuthenticated(auth)) {
                        return Mono.error(PushException.unauthenticated("the export needs an authenticated caller"));
                    }
                    String role = properties.getAdmin().getRole();
                    if (!PushAdminGate.holdsRole(auth, role)) {
                        return Mono.error(
                                PushException.forbidden("role " + role + " required (shiguchi.cursus.admin.role)"));
                    }
                    if (!report.archive().searchCapabilities().enabled()) {
                        return Mono.error(
                                PushException.notFound(
                                        "no archive: set shiguchi.cursus.archive.retention above zero (store POSTGRES or MEMORY)"));
                    }
                    PushArchive.Query query = query(request);
                    Map<String, Object> arguments = new LinkedHashMap<>();
                    request.queryParams().forEach((k, v) -> arguments.put(k, v.size() == 1 ? v.get(0) : v));
                    AtomicLong lines = new AtomicLong();
                    Flux<String> body = report.archive()
                            .search(query)
                            .map(entry -> line(report.render(entry)))
                            .doOnNext(l -> lines.incrementAndGet())
                            .doOnComplete(() -> report.audit()
                                    .record(auth.getName(), "export", arguments, "ok " + lines.get() + " line(s)"))
                            .doOnError(e -> report.audit().record(auth.getName(), "export", arguments, "error"));
                    return ServerResponse.ok()
                            .contentType(NDJSON)
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"push-export.ndjson\"")
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .body(body, String.class);
                })
                .onErrorResume(PushException.class, PushResponses::error);
    }

    static PushArchive.Query query(ServerRequest request) {
        String limit = request.queryParam("limit").orElse(null);
        int n = DEFAULT_LIMIT;
        if (limit != null && !limit.isBlank()) {
            try {
                n = Integer.parseInt(limit.trim());
            } catch (NumberFormatException e) {
                throw PushException.badRequest("limit must be a number, got '" + limit + "'");
            }
            if (n <= 0) throw PushException.badRequest("limit must be above 0");
            n = Math.min(n, MAX_LIMIT);
        }
        return new PushArchive.Query(
                request.queryParam("topic").orElse(null),
                PushEndpoint.instant("from", request.queryParam("from").orElse(null)),
                PushEndpoint.instant("to", request.queryParam("to").orElse(null)),
                request.queryParam("after").orElse(null),
                request.queryParam("before").orElse(null),
                PushEndpoint.attributes(request.queryParam("attr").orElse(null)),
                request.queryParam("type").orElse(null),
                request.queryParam("q").orElse(null),
                n);
    }

    private static String line(Map<String, Object> rendered) {
        try {
            return PushResponses.JSON.writeValueAsString(rendered) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
