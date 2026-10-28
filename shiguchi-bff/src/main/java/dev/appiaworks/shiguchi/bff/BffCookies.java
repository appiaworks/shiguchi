package dev.appiaworks.shiguchi.bff;

import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;

/** The session cookie and the login cookie: the one place their attributes are decided. */
final class BffCookies {

    private static final String HOST_PREFIX = "__Host-";

    private final BffProperties.Cookie settings;
    private final Duration loginTimeout;
    private final Duration persistentMaxAge;

    BffCookies(BffProperties properties) {
        this.settings = properties.getCookie();
        this.loginTimeout = properties.getLoginTimeout();
        this.persistentMaxAge = properties.getSession().getAbsoluteTimeout();
    }

    /** The session cookie; a browser-session cookie unless {@code cookie.persistent}. */
    ResponseCookie session(String value) {
        ResponseCookie.ResponseCookieBuilder builder =
                base(settings.getName(), value).sameSite(sameSite(settings.getSameSite()));
        if (settings.isPersistent()) builder.maxAge(persistentMaxAge);
        return builder.build();
    }

    /** Expires the session cookie. */
    ResponseCookie clearSession() {
        return base(settings.getName(), "")
                .sameSite(sameSite(settings.getSameSite()))
                .maxAge(Duration.ZERO)
                .build();
    }

    /**
     * The cookie that binds a browser to its sign-in: the value is the {@code state}. One fixed name,
     * so a client that starts many sign-ins cannot fill the browser's cookie jar; a newer sign-in
     * replaces the older one's.
     */
    ResponseCookie login(String state) {
        return base(loginCookieName(), state)
                .sameSite("Lax")
                .maxAge(loginTimeout)
                .build();
    }

    /** Expires the login cookie. */
    ResponseCookie clearLogin() {
        return base(loginCookieName(), "").sameSite("Lax").maxAge(Duration.ZERO).build();
    }

    /** The session cookie's value, or {@code null}. */
    String sessionValue(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(settings.getName());
        return cookie == null || cookie.getValue().isEmpty() ? null : cookie.getValue();
    }

    /** The value of the login cookie, or {@code null}. */
    String loginValue(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(loginCookieName());
        return cookie == null || cookie.getValue().isEmpty() ? null : cookie.getValue();
    }

    /** {@code __Host-gw-login} when the cookies are Secure (the prefix requires it), else {@code gw-login}. */
    String loginCookieName() {
        return (settings.isSecure() ? HOST_PREFIX : "") + "gw-login";
    }

    /** Whether a cookie of this name is one the BFF alone may set: the session cookie or the login cookie. */
    boolean isOwnCookie(String name) {
        return name.equals(settings.getName()) || name.equals(loginCookieName());
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(settings.isSecure())
                .path("/");
    }

    private static String sameSite(BffProperties.SameSite sameSite) {
        String lower = sameSite.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
