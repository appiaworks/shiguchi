package dev.appiaworks.shiguchi.bff;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Keeps anything but the BFF's own endpoints from setting the session cookie or a login cookie on
 * the browser: just before the response commits (and after every other commit action registered
 * by then), a {@code Set-Cookie} whose name is the session cookie's or the login cookie's
 * is dropped. Requests under the BFF path are served by {@link BffEndpoints} alone,
 * with no backend behind them, so they are left as written. Everything else a response sets is
 * untouched; {@code rest.forward-response-headers} cannot name {@code set-cookie} while the
 * module is on (startup check).
 */
final class BffSetCookieGuard implements WebFilter, Ordered {

    private static final String SET_COOKIE = "Set-Cookie";

    private final BffCookies cookies;
    private final String path;

    BffSetCookieGuard(BffCookies cookies, String path) {
        this.cookies = cookies;
        this.path = path;
    }

    @Override
    public int getOrder() {
        // Before the security chain (-100), so the decorator wraps everything below it.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestPath = exchange.getRequest().getPath().value();
        if (requestPath.equals(path) || requestPath.startsWith(path + "/")) return chain.filter(exchange);
        return chain.filter(
                exchange.mutate().response(new Guarded(exchange.getResponse())).build());
    }

    /** Registers the cleanup as the last commit action, once, when the body or completion is first written. */
    private final class Guarded extends ServerHttpResponseDecorator {

        private final AtomicBoolean registered = new AtomicBoolean();

        Guarded(ServerHttpResponse delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            register();
            return super.writeWith(body);
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            register();
            return super.writeAndFlushWith(body);
        }

        @Override
        public Mono<Void> setComplete() {
            register();
            return super.setComplete();
        }

        private void register() {
            if (registered.compareAndSet(false, true)) {
                getDelegate().beforeCommit(() -> Mono.fromRunnable(this::sanitize));
            }
        }

        private void sanitize() {
            ServerHttpResponse response = getDelegate();
            for (String name : new ArrayList<>(response.getCookies().keySet())) {
                if (cookies.isOwnCookie(name)) response.getCookies().remove(name);
            }
            // forEach and put(String, List) are the only calls on HttpHeaders that exist with the same
            // signature in Spring 6 and 7 (get, remove and getOrEmpty take an Object in 6, a String in 7).
            List<String> raw = new ArrayList<>();
            response.getHeaders().forEach((name, values) -> {
                if (name.equalsIgnoreCase(SET_COOKIE)) raw.addAll(values);
            });
            if (raw.isEmpty()) return;
            List<String> kept = new ArrayList<>();
            for (String header : raw) {
                int equals = header.indexOf('=');
                String name = (equals < 0 ? header : header.substring(0, equals)).trim();
                if (!cookies.isOwnCookie(name)) kept.add(header);
            }
            if (kept.size() != raw.size()) response.getHeaders().put(SET_COOKIE, kept);
        }
    }
}
