package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where the gateway is and how to authenticate, resolved from flags, the environment and a profile
 * of {@code ~/.cursus/config.yml} (in that order).
 */
public record Settings(
        String url,
        String actuatorUrl,
        String pushPath,
        String grpcTarget,
        TokenSource token,
        String basic,
        boolean insecure) {

    static final String DEFAULT_URL = "http://localhost:8080";

    /** The base of the {@code gateway-push} actuator endpoint. */
    public String adminBase() {
        return actuatorUrl + "/gateway-push";
    }

    /** The push surface's base URL, {@code <url><push path>}. */
    public String pushBase() {
        return url + pushPath;
    }

    /** True when the gateway is addressed over TLS. */
    boolean tls() {
        return url.startsWith("https://");
    }

    /** The {@code host:port} of the gRPC port: the gateway's host on 9090 unless set. */
    String grpcAuthority() {
        if (grpcTarget != null && !grpcTarget.isBlank()) return grpcTarget;
        return URI.create(url).getHost() + ":9090";
    }

    /** Flags as given on the command line; null for "not given". */
    record Flags(
            String profile,
            String url,
            String actuatorUrl,
            String pushPath,
            String grpcTarget,
            String token,
            String tokenCmd,
            String basic,
            Boolean insecure) {}

    static Settings resolve(Flags f, Map<String, String> env) {
        String profileName = first(f.profile(), env.get("CURSUS_PROFILE"));
        JsonNode profile = loadProfile(profileName, env);
        String url = strip(first(f.url(), env.get("CURSUS_URL"), text(profile, "url"), DEFAULT_URL));
        String actuator = strip(first(f.actuatorUrl(), env.get("CURSUS_ACTUATOR_URL"), text(profile, "actuator-url")));
        if (actuator == null) actuator = url + "/actuator";
        String pushPath = first(f.pushPath(), text(profile, "push-path"), "/push");
        if (!pushPath.startsWith("/")) pushPath = "/" + pushPath;
        pushPath = strip(pushPath);
        String token = first(f.token(), env.get("CURSUS_TOKEN"), text(profile, "token"));
        String tokenCmd = first(f.tokenCmd(), env.get("CURSUS_TOKEN_CMD"), text(profile, "token-cmd"));
        String basic = first(f.basic(), text(profile, "basic"));
        boolean insecure = f.insecure() != null
                ? f.insecure()
                : profile != null && profile.path("insecure").asBoolean(false);
        return new Settings(
                url,
                actuator,
                pushPath,
                first(f.grpcTarget(), text(profile, "grpc-target")),
                new TokenSource(token, tokenCmd),
                basic,
                insecure);
    }

    private static JsonNode loadProfile(String name, Map<String, String> env) {
        File file = configFile(env);
        if (!file.isFile()) {
            if (name != null) throw usage("profile '" + name + "' requested but " + file + " does not exist");
            return null;
        }
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(file);
        } catch (IOException e) {
            throw usage("cannot read " + file + ": " + e.getMessage());
        }
        JsonNode profiles = root == null ? null : root.path("profiles");
        String wanted = name != null ? name : "default";
        JsonNode profile = profiles == null ? null : profiles.get(wanted);
        if (profile == null && name != null) throw usage("no profile '" + name + "' in " + file);
        return profile;
    }

    private static File configFile(Map<String, String> env) {
        String override = env.get("CURSUS_CONFIG");
        if (override != null && !override.isBlank()) return new File(override);
        return Path.of(System.getProperty("user.home"), ".cursus", "config.yml").toFile();
    }

    private static CliException usage(String message) {
        return new CliException(CliException.USAGE, message);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private static String strip(String value) {
        if (value == null) return null;
        String v = value.strip();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
