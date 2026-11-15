package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceDefinition.isExposedByTags")
class ServiceDefinitionExposeTagsTest {

    @Test
    @DisplayName("GIVEN no allowlist WHEN a method has no tags SHOULD be exposed")
    void GIVEN_noAllowlist_WHEN_untaggedMethod_SHOULD_beExposed() {
        assertThat(new ServiceDefinition().isExposedByTags(List.of(), List.of()))
                .isTrue();
        assertThat(new ServiceDefinition().isExposedByTags(List.of(), null)).isTrue();
    }

    @Test
    @DisplayName("GIVEN an allowlist WHEN a method carries one of its tags SHOULD be exposed")
    void GIVEN_allowlist_WHEN_methodHasListedTag_SHOULD_beExposed() {
        assertThat(new ServiceDefinition().isExposedByTags(List.of("pii", "external"), List.of("external")))
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN an allowlist WHEN a method is untagged SHOULD be hidden")
    void GIVEN_allowlist_WHEN_untaggedMethod_SHOULD_beHidden() {
        assertThat(new ServiceDefinition().isExposedByTags(List.of(), List.of("external")))
                .isFalse();
        assertThat(new ServiceDefinition().isExposedByTags(null, List.of("external")))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN an allowlist WHEN a method tag differs in case or spelling SHOULD be hidden")
    void GIVEN_allowlist_WHEN_typoOrCaseMismatch_SHOULD_beHidden() {
        assertThat(new ServiceDefinition().isExposedByTags(List.of("External", "extrenal"), List.of("external")))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a service allowlist WHEN a global one is set SHOULD use the service's own")
    void GIVEN_serviceAllowlist_WHEN_globalSet_SHOULD_useServiceList() {
        ServiceDefinition svc = new ServiceDefinition();
        svc.setExposeTags(new ArrayList<>(List.of("partner")));

        assertThat(svc.isExposedByTags(List.of("partner"), List.of("external"))).isTrue();
        assertThat(svc.isExposedByTags(List.of("external"), List.of("external")))
                .isFalse();
    }
}
