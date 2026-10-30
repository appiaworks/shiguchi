package dev.appiaworks.shiguchi.ui.webmvc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.ui.autoconfigure.ServletScanExcludes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Drift guard for the servlet UI's component scan. The scan covers all of {@code
 * dev.appiaworks.shiguchi} and leaves out the reactive-only classes by name
 * ({@link ServletScanExcludes}). A new component that needs WebFlux, the reactive security API or
 * Spring Cloud Gateway would break every servlet application at startup, so this test reads the
 * constant pool of each scanned component class and fails until the class is excluded, guarded by
 * {@code @ConditionalOnClass} on a Spring Cloud Gateway type, or guarded by
 * {@code @ConditionalOnWebApplication(type = REACTIVE)}.
 */
@DisplayName("Servlet UI component scan drift guard")
class ServletScanDriftGuardTest {

    /** Constant-pool fragments that only resolve with WebFlux, reactive security or SCG on the classpath. */
    private static final List<String> REACTIVE_ONLY = List.of(
            "org/springframework/web/reactive/",
            "org/springframework/http/server/reactive/",
            "org/springframework/cloud/gateway/",
            "ServerHttpSecurity",
            "ReactiveSecurityContextHolder");

    @Test
    @DisplayName("GIVEN the excluded class names WHEN loading them SHOULD all name classes that exist")
    void given_excludeList_when_loading_should_allExist() {
        for (String name : ServletScanExcludes.CLASS_NAMES) {
            assertThat(new ClassPathResource(name.replace('.', '/') + ".class").exists())
                    .as("excluded class %s must exist; a renamed class silently drops out of the list", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName(
            "GIVEN the scanned components WHEN reading their bytecode SHOULD find no unguarded reactive-only reference")
    void given_scannedComponents_when_readingBytecode_should_findNoUnguardedReactiveReference() throws IOException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        for (var candidate : scanner.findCandidateComponents("dev.appiaworks.shiguchi")) {
            String className = candidate.getBeanClassName();
            AnnotationMetadata metadata = ((AnnotatedBeanDefinition) candidate).getMetadata();
            if (className.startsWith("dev.appiaworks.shiguchi.ui.webmvc.")
                    || metadata.isAnnotated(SpringBootConfiguration.class.getName())
                    || ServletScanExcludes.CLASS_NAMES.contains(className)) {
                continue;
            }
            inspected++;
            String reference = reactiveOnlyReference(className);
            if (reference != null && !isGuarded(metadata)) {
                offenders.add(className + " references " + reference);
            }
        }

        assertThat(inspected)
                .as("the scan should find the gateway's components")
                .isGreaterThan(10);
        assertThat(offenders)
                .as("add these to ServletScanExcludes.CLASS_NAMES, or guard them with @ConditionalOnClass(name = SCG"
                        + " class) or @ConditionalOnWebApplication(type = REACTIVE)")
                .isEmpty();
    }

    private static String reactiveOnlyReference(String className) throws IOException {
        try (InputStream in = new ClassPathResource(className.replace('.', '/') + ".class").getInputStream()) {
            // Class names are stored in the constant pool as (modified) UTF-8; for these ASCII
            // names a Latin-1 view of the bytes is an exact substring search.
            String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return REACTIVE_ONLY.stream().filter(bytes::contains).findFirst().orElse(null);
        }
    }

    private static boolean isGuarded(AnnotationMetadata metadata) {
        Map<String, Object> onClass = metadata.getAnnotationAttributes(ConditionalOnClass.class.getName(), true);
        if (onClass != null) {
            List<String> named = new ArrayList<>();
            named.addAll(List.of((String[]) onClass.get("value")));
            named.addAll(List.of((String[]) onClass.get("name")));
            if (named.stream().anyMatch(n -> n.startsWith("org.springframework.cloud.gateway."))) {
                return true;
            }
        }
        Map<String, Object> onWeb = metadata.getAnnotationAttributes(ConditionalOnWebApplication.class.getName());
        return onWeb != null && "REACTIVE".equals(String.valueOf(onWeb.get("type")));
    }
}
