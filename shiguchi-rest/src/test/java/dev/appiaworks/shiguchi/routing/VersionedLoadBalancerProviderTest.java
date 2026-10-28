package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version-routing policy's config: what the gateway writes for {@code services[].versions},
 * and that gRPC's provider parses it back (or refuses a bad one instead of misrouting).
 */
@DisplayName("Version routing policy config")
class VersionedLoadBalancerProviderTest {

    private static ServiceDefinition service() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("catalog");
        def.setDiscoveryName("catalog");
        return def;
    }

    private static ServiceDefinition.TrafficSplit split(String version, int weight) {
        ServiceDefinition.TrafficSplit split = new ServiceDefinition.TrafficSplit();
        split.setVersion(version);
        split.setWeight(weight);
        return split;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get(VersionedLoadBalancingConfig.POLICY);
    }

    @Test
    @DisplayName(
            "GIVEN no split and no preview header WHEN the config is built SHOULD emit nothing, leaving balancing as it was")
    void given_noRouting_when_built_should_emitNothing() {
        assertThat(VersionedLoadBalancingConfig.forService(service(), "round_robin"))
                .isNull();
    }

    @Test
    @DisplayName("GIVEN a split with headers WHEN built and parsed SHOULD round-trip weights, headers and child policy")
    void given_split_when_builtAndParsed_should_roundTrip() {
        ServiceDefinition def = service();
        def.getVersions().setTraffic(List.of(split("1.4.0", 90), split(" 1.10.0 ", 10)));
        def.getVersions().setStickyHeader("X-User");
        def.getVersions().setPreviewHeader("X-Backend-Version");

        Map<String, Object> entry = VersionedLoadBalancingConfig.forService(def, "round_robin");
        assertThat(body(entry))
                .containsEntry("metadataKey", "version")
                .containsEntry("stickyHeader", "x-user")
                .containsEntry("previewHeader", "x-backend-version")
                .containsEntry("childPolicy", "round_robin");
        assertThat(body(entry).get("traffic"))
                .isEqualTo(List.of(
                        Map.of("version", "1.4.0", "weight", 90.0), Map.of("version", "1.10.0", "weight", 10.0)));

        NameResolver.ConfigOrError parsed =
                new VersionedLoadBalancerProvider().parseLoadBalancingPolicyConfig(body(entry));
        assertThat(parsed.getError()).isNull();
        var config = (VersionedLoadBalancerProvider.Config) parsed.getConfig();
        assertThat(config.traffic())
                .containsExactly(
                        new VersionedLoadBalancerProvider.VersionWeight("1.4.0", 90),
                        new VersionedLoadBalancerProvider.VersionWeight("1.10.0", 10));
        assertThat(config.stickyKey().name()).isEqualTo("x-user");
        assertThat(config.previewKey().name()).isEqualTo("x-backend-version");
    }

    @Test
    @DisplayName("GIVEN a negative weight or an unknown child policy WHEN parsed SHOULD refuse the config")
    void given_badConfig_when_parsed_should_refuse() {
        var provider = new VersionedLoadBalancerProvider();

        assertThat(provider.parseLoadBalancingPolicyConfig(
                                Map.of("traffic", List.of(Map.of("version", "1", "weight", -1.0))))
                        .getError())
                .isNotNull();
        assertThat(provider.parseLoadBalancingPolicyConfig(Map.of("childPolicy", "no_such_policy"))
                        .getError())
                .isNotNull();
    }

    @Test
    @DisplayName("GIVEN the channel factory loaded WHEN gRPC is asked for the policy by name SHOULD find it registered")
    void given_factoryLoaded_when_policyLookedUp_should_beRegistered() throws Exception {
        Class.forName(GrpcChannelFactory.class.getName());

        assertThat(LoadBalancerRegistry.getDefaultRegistry().getProvider(VersionedLoadBalancingConfig.POLICY))
                .isInstanceOf(VersionedLoadBalancerProvider.class);
    }

    @Test
    @DisplayName("GIVEN a split WHEN outlier detection is on as well SHOULD nest the version policy inside it")
    void given_splitAndOutlierDetection_when_built_should_nest() {
        ServiceDefinition def = service();
        def.getVersions().setTraffic(List.of(split("2", 100)));
        def.getOutlierDetection().setEnabled(true);

        var versioned = VersionedLoadBalancingConfig.forService(def, "round_robin");
        var lb = OutlierDetectionServiceConfig.loadBalancingConfig(def, List.of(versioned));

        @SuppressWarnings("unchecked")
        Map<String, Object> od = (Map<String, Object>) lb.get(0).get(OutlierDetectionServiceConfig.POLICY);
        assertThat(od.get("childPolicy")).isEqualTo(List.of(versioned));
    }

    @Test
    @DisplayName(
            "GIVEN a split WHEN the schema version is chosen SHOULD pick among versions receiving calls, OLDEST meaning the oldest of those")
    void given_split_when_schemaChosen_should_ignoreUnweightedVersions() {
        var config = new ServiceDefinition.VersionsConfig();
        config.setTraffic(List.of(split("1.4.0", 0), split("1.10.0", 100)));

        var served = SchemaSourceSelector.servedVersions(new LinkedHashSet<>(List.of("1.4.0", "1.10.0")), config);

        assertThat(served).containsExactly("1.10.0");
        assertThat(SchemaSourceSelector.chooseVersion(served, "OLDEST")).isEqualTo("1.10.0");
        // When no weighted version runs, every running version stays eligible.
        assertThat(SchemaSourceSelector.servedVersions(new LinkedHashSet<>(List.of("0.9")), config))
                .containsExactly("0.9");
    }
}
