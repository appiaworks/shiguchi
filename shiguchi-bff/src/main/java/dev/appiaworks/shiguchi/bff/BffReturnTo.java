package dev.appiaworks.shiguchi.bff;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides which application a {@code return_to} belongs to. A login is a top-level navigation with
 * no {@code Origin}, so the application comes from where the browser is going back to: the URL must
 * lie under exactly one application's {@code return-to} entries (same origin, path on a segment
 * boundary).
 */
final class BffReturnTo {

    /** The application a {@code return_to} resolved to, and the URL to send the browser to. */
    record Match(BffApplications.Resolved application, String returnTo) {}

    private record Entry(BffApplications.Resolved application, String origin, String path) {}

    private final List<Entry> entries = new ArrayList<>();
    private final BffApplications applications;

    BffReturnTo(BffApplications applications) {
        this.applications = applications;
        for (BffApplications.Resolved app : applications.all()) {
            for (String configured : app.settings().getReturnTo()) {
                URI uri = parse(configured);
                if (uri == null) continue;
                String origin = BffApplications.normalizeOrigin(uri);
                if (origin != null) entries.add(new Entry(app, origin, pathOf(uri)));
            }
        }
    }

    /**
     * @param returnTo the request's {@code return_to}, may be {@code null}
     * @return the match, or empty when the value is unusable, matches no application or several
     */
    Optional<Match> resolve(String returnTo) {
        if (returnTo == null || returnTo.isEmpty()) {
            List<BffApplications.Resolved> all = applications.all();
            if (all.size() != 1 || all.get(0).settings().getReturnTo().isEmpty()) return Optional.empty();
            BffApplications.Resolved only = all.get(0);
            return Optional.of(new Match(only, only.settings().getReturnTo().get(0)));
        }
        if (!clean(returnTo)) return Optional.empty();
        URI uri = parse(returnTo);
        if (uri == null || uri.getRawUserInfo() != null) return Optional.empty();
        String origin = BffApplications.normalizeOrigin(uri);
        if (origin == null) return Optional.empty();
        String path = pathOf(uri);
        BffApplications.Resolved found = null;
        for (Entry entry : entries) {
            if (!entry.origin().equals(origin) || !underPath(entry.path(), path)) continue;
            if (found != null && found != entry.application()) return Optional.empty();
            found = entry.application();
        }
        return found == null ? Optional.empty() : Optional.of(new Match(found, returnTo));
    }

    private static boolean underPath(String entryPath, String candidate) {
        return candidate.equals(entryPath)
                || (entryPath.endsWith("/") && candidate.startsWith(entryPath))
                || candidate.startsWith(entryPath + "/");
    }

    private static String pathOf(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static URI parse(String value) {
        try {
            return new URI(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** No backslash, control character or space anywhere: browsers read those differently from {@link URI}. */
    private static boolean clean(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c <= 0x20 || c == 0x7f) return false;
        }
        return true;
    }
}
