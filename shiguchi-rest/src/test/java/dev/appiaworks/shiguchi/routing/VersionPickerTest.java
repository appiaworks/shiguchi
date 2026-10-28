package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the version-routing picker chooses a version per call: the weights, the sticky key, the
 * preview header, and what happens while versions connect or are down.
 */
@DisplayName("Version picker")
class VersionPickerTest {

    private static final Metadata.Key<String> USER = Metadata.Key.of("x-user", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> PREVIEW =
            Metadata.Key.of("x-backend-version", Metadata.ASCII_STRING_MARSHALLER);

    /** A version's own picker, standing in for its round-robin child: it answers with its version. */
    private static LoadBalancer.SubchannelPicker answering(String version) {
        return new LoadBalancer.SubchannelPicker() {
            @Override
            public LoadBalancer.PickResult pickSubchannel(LoadBalancer.PickSubchannelArgs args) {
                return LoadBalancer.PickResult.withError(Status.UNAVAILABLE.withDescription(version));
            }
        };
    }

    private static VersionedLoadBalancer.VersionPicker.Group group(
            String version, double weight, ConnectivityState state) {
        return new VersionedLoadBalancer.VersionPicker.Group(version, weight, state, answering(version));
    }

    private static VersionedLoadBalancer.VersionPicker.Group ready(String version, double weight) {
        return group(version, weight, ConnectivityState.READY);
    }

    private static LoadBalancer.PickSubchannelArgs args(Metadata headers) {
        LoadBalancer.PickSubchannelArgs args = mock(LoadBalancer.PickSubchannelArgs.class);
        when(args.getHeaders()).thenReturn(headers);
        return args;
    }

    private static Metadata headers(Metadata.Key<String> key, String value) {
        Metadata m = new Metadata();
        if (key != null) m.put(key, value);
        return m;
    }

    /** The version a pick went to, or WAIT for a queued call. */
    private static String pick(VersionedLoadBalancer.VersionPicker picker, Metadata headers) {
        LoadBalancer.PickResult result = picker.pickSubchannel(args(headers));
        if (result.getStatus().isOk() && result.getSubchannel() == null) return "WAIT";
        return result.getStatus().getDescription();
    }

    private static VersionedLoadBalancer.VersionPicker picker(List<VersionedLoadBalancer.VersionPicker.Group> groups) {
        return new VersionedLoadBalancer.VersionPicker(groups, USER, PREVIEW);
    }

    @Test
    @DisplayName(
            "GIVEN a 90/10 split and no sticky key WHEN 20000 calls are picked SHOULD send about 10 % to the new version")
    void given_ninetyTen_when_manyPicks_should_matchWeights() {
        var picker = picker(List.of(ready("1.4.0", 90), ready("1.10.0", 10)));

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20_000; i++) counts.merge(pick(picker, new Metadata()), 1, Integer::sum);

        assertThat(counts).containsOnlyKeys("1.4.0", "1.10.0");
        assertThat(counts.get("1.10.0")).isBetween(1_600, 2_400);
    }

    @Test
    @DisplayName("GIVEN a sticky key WHEN the same caller is picked many times SHOULD always get the same version")
    void given_stickyKey_when_pickedRepeatedly_should_beStable() {
        var picker = picker(List.of(ready("1.4.0", 50), ready("1.10.0", 50)));

        for (int user = 0; user < 200; user++) {
            String first = pick(picker, headers(USER, "user-" + user));
            for (int i = 0; i < 5; i++) {
                assertThat(pick(picker, headers(USER, "user-" + user))).isEqualTo(first);
            }
        }
    }

    @Test
    @DisplayName(
            "GIVEN sticky callers WHEN the newest version's weight is raised step by step SHOULD only ever move callers onto it, never back")
    void given_stickyCallers_when_newestWeightRaised_should_onlyMoveForward() {
        int[] steps = {5, 25, 50, 75, 100};
        Map<String, String> previous = new HashMap<>();
        for (int newest : steps) {
            var picker = picker(List.of(ready("1.4.0", 100 - newest), ready("1.10.0", newest)));
            for (int user = 0; user < 500; user++) {
                String key = "user-" + user;
                String now = pick(picker, headers(USER, key));
                if ("1.10.0".equals(previous.get(key))) {
                    assertThat(now).as("%s moved back at %d%%", key, newest).isEqualTo("1.10.0");
                }
                previous.put(key, now);
            }
        }
        assertThat(previous.values()).containsOnly("1.10.0");
    }

    @Test
    @DisplayName(
            "GIVEN anonymous callers under a sticky key WHEN picked SHOULD be spread per call rather than pinned together")
    void given_anonymousCallers_when_picked_should_beSpread() {
        var picker = picker(List.of(ready("1.4.0", 50), ready("1.10.0", 50)));

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 2_000; i++) counts.merge(pick(picker, headers(USER, "anonymous")), 1, Integer::sum);

        assertThat(counts).containsOnlyKeys("1.4.0", "1.10.0");
    }

    @Test
    @DisplayName("GIVEN a preview header naming a running version at weight 0 WHEN picked SHOULD go to that version")
    void given_previewHeader_when_picked_should_overrideWeights() {
        var picker = picker(List.of(ready("1.4.0", 100), ready("1.10.0", 0)));

        assertThat(pick(picker, headers(PREVIEW, "1.10.0"))).isEqualTo("1.10.0");
        assertThat(pick(picker, headers(PREVIEW, "v1.10")))
                .as("same version, other spelling")
                .isEqualTo("1.10.0");
        assertThat(pick(picker, new Metadata())).isEqualTo("1.4.0");
    }

    @Test
    @DisplayName("GIVEN a preview header naming a version still connecting WHEN picked SHOULD wait for it")
    void given_previewVersionConnecting_when_picked_should_wait() {
        var picker = picker(List.of(ready("1.4.0", 100), group("1.10.0", 0, ConnectivityState.CONNECTING)));

        assertThat(pick(picker, headers(PREVIEW, "1.10.0"))).isEqualTo("WAIT");
    }

    @Test
    @DisplayName(
            "GIVEN the only weighted version still connecting WHEN picked SHOULD wait for it, not send the call to the unweighted version")
    void given_weightedConnecting_when_picked_should_wait() {
        var picker = picker(List.of(ready("1.4.0", 0), group("1.10.0", 100, ConnectivityState.CONNECTING)));

        assertThat(pick(picker, new Metadata())).isEqualTo("WAIT");
    }

    @Test
    @DisplayName("GIVEN the only weighted version down WHEN picked SHOULD fall back to a running version")
    void given_weightedDown_when_picked_should_fallBack() {
        var picker = picker(List.of(ready("1.4.0", 0), group("1.10.0", 100, ConnectivityState.TRANSIENT_FAILURE)));

        assertThat(pick(picker, new Metadata())).isEqualTo("1.4.0");
    }

    @Test
    @DisplayName(
            "GIVEN a sticky caller whose version is still connecting WHEN picked SHOULD wait, keeping the caller's place instead of moving it")
    void given_stickyVersionConnecting_when_picked_should_waitNotMove() {
        var allUp = picker(List.of(ready("1.4.0", 50), ready("1.10.0", 50)));
        var newConnecting = picker(List.of(ready("1.4.0", 50), group("1.10.0", 50, ConnectivityState.CONNECTING)));

        for (int user = 0; user < 100; user++) {
            Metadata who = headers(USER, "user-" + user);
            String settled = pick(allUp, who);
            String meanwhile = pick(newConnecting, who);
            assertThat(meanwhile).isEqualTo(settled.equals("1.10.0") ? "WAIT" : "1.4.0");
        }
    }

    @Test
    @DisplayName("GIVEN every version failing WHEN picked SHOULD fail the call with a version's own error")
    void given_allDown_when_picked_should_fail() {
        var picker = picker(List.of(
                group("1.4.0", 50, ConnectivityState.TRANSIENT_FAILURE),
                group("1.10.0", 50, ConnectivityState.TRANSIENT_FAILURE)));

        assertThat(pick(picker, new Metadata())).isEqualTo("1.4.0");
    }
}
