package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Exposure by tag ({@code shiguchi.exposure.*}).
 *
 * <p>An allowlist: with {@link #exposeTags} set, a method exists only if its tags
 * ({@code (shiguchi.method).tags}, or {@code operation-tags} for {@code REST_DOWNSTREAM})
 * contain at least one listed tag. Untagged methods are hidden, so a typo hides a method instead
 * of leaking it. A hidden method is not routed on REST, gRPC-Web, GraphQL or the gRPC proxy and is
 * absent from OpenAPI, the routes report and gRPC reflection: it answers like a method that does
 * not exist. Re-evaluated on every schema refresh.
 */
@Data
@NoArgsConstructor
public class ExposureConfig {

    /**
     * Tags a method must carry (at least one, exact and case-sensitive) to be exposed. Empty (the
     * default) exposes everything. A service's own {@code expose-tags} overrides this list.
     */
    private List<String> exposeTags = new ArrayList<>();
}
