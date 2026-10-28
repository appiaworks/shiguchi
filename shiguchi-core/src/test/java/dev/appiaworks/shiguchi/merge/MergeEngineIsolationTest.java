package dev.appiaworks.shiguchi.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the merge engine's independence from the rest of the gateway.
 *
 * <p>The engine is developed on {@code main} but has to cherry-pick onto release branches that are
 * missing whole features it was written alongside — the paging/sorting convention layer, for one.
 * That works only while the engine depends on nothing inside {@code dev.appiaworks.shiguchi} beyond its own
 * package: the moment it imports a routing or serde class, a backport stops applying cleanly and
 * starts needing whatever that class dragged in.
 *
 * <p>This is a structural test, so it fails at the commit that would break the property rather than
 * months later when someone tries the cherry-pick.
 */
class MergeEngineIsolationTest {

    private static final Path ENGINE_SOURCES = Path.of("src/main/java/dev/appiaworks/shiguchi/merge");

    /**
     * Packages the engine may depend on. Deliberately narrow: the JDK, Reactor, protobuf, and the
     * Jackson databind used to encode cursors — all third-party or platform, none of them carrying
     * gateway state.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "import java.",
            "import javax.",
            "import reactor.",
            "import com.google.protobuf.",
            "import com.fasterxml.jackson.",
            "import dev.appiaworks.shiguchi.merge.");

    private static List<Path> engineSources() throws IOException {
        try (Stream<Path> paths = Files.walk(ENGINE_SOURCES)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("GIVEN the merge engine WHEN inspecting its imports SHOULD depend on nothing else in the gateway")
    void given_mergeEngine_when_inspectingImports_should_dependOnNothingElseInTheGateway() throws IOException {
        List<String> offenders = collectImports().stream()
                .filter(entry -> ALLOWED_PREFIXES.stream().noneMatch(entry.importLine()::startsWith))
                .map(entry -> entry.file() + " → " + entry.importLine())
                .toList();

        assertThat(offenders)
                .as("the merge engine must stay self-contained so it cherry-picks onto release "
                        + "branches that lack the features it was written alongside")
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN the merge engine WHEN inspecting its imports SHOULD not reach into routing or serde")
    void given_mergeEngine_when_inspectingImports_should_notReachIntoRoutingOrSerde() throws IOException {
        List<String> gatewayImports = collectImports().stream()
                .map(ImportEntry::importLine)
                .filter(line -> line.startsWith("import dev.appiaworks.shiguchi."))
                .filter(line -> !line.startsWith("import dev.appiaworks.shiguchi.merge."))
                .toList();

        // Named explicitly because these are the tempting ones: the engine consumes sort fields and
        // page tokens, so reaching for ConventionArgMapper or RouteDefinition looks natural and is
        // exactly what would couple it to a feature the release branches do not have.
        assertThat(gatewayImports).isEmpty();
    }

    @Test
    @DisplayName("GIVEN the merge engine WHEN counting its sources SHOULD find the expected classes, "
            + "so this guard cannot silently pass on an empty scan")
    void given_mergeEngine_when_countingSources_should_findExpectedClasses() throws IOException {
        List<String> names = engineSources().stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();

        assertThat(names)
                .containsExactly(
                        "HeapMerge.java",
                        "MergeCursor.java",
                        "MergeCursorCodec.java",
                        "MergeSource.java",
                        "MergeSpec.java",
                        "SortComparator.java");
    }

    private List<ImportEntry> collectImports() throws IOException {
        List<ImportEntry> imports = new java.util.ArrayList<>();
        for (Path source : engineSources()) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("import ")) {
                    imports.add(new ImportEntry(
                            source.getFileName().toString(), trimmed.replace("import static ", "import ")));
                }
            }
        }
        return imports;
    }

    private record ImportEntry(String file, String importLine) {}
}
