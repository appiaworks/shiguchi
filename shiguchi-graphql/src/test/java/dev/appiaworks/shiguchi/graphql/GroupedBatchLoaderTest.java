package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("GroupedBatchLoader")
class GroupedBatchLoaderTest {

    private static Map<String, Object> order(String id, Object customerId) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("customerId", customerId);
        return m;
    }

    @Test
    @DisplayName("GIVEN a flat item source WHEN grouped SHOULD make one call and group by the parent field in order")
    void GIVEN_itemSource_WHEN_grouped_SHOULD_oneCallAndGroupInOrder() {
        AtomicInteger calls = new AtomicInteger();
        GroupedBatchLoader loader = new GroupedBatchLoader(
                (keys, md) -> {
                    calls.incrementAndGet();
                    return Mono.just(List.of(order("3", "c1"), order("1", "c2"), order("2", "c1")));
                },
                "customerId",
                0);

        StepVerifier.create(loader.loadGrouped(List.of("c1", "c2"), new Metadata()))
                .assertNext(outcome -> {
                    assertThat(outcome.groups().get("c1"))
                            .extracting(o -> o.get("id"))
                            .containsExactly("3", "2");
                    assertThat(outcome.groups().get("c2")).hasSize(1);
                    assertThat(outcome.hasFailures()).isFalse();
                })
                .verifyComplete();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName(
            "GIVEN a parent with no children and a stray child WHEN grouped SHOULD give an empty list and drop the stray")
    void GIVEN_parentWithoutChildrenAndStray_WHEN_grouped_SHOULD_emptyListAndDropStray() {
        GroupedBatchLoader loader = new GroupedBatchLoader(
                (keys, md) -> Mono.just(List.of(order("1", "c1"), order("9", "other"), order("8", null))),
                "customerId",
                0);

        StepVerifier.create(loader.loadGrouped(List.of("c1", "c2"), new Metadata()))
                .assertNext(outcome -> {
                    assertThat(outcome.groups()).containsOnlyKeys("c1", "c2");
                    assertThat(outcome.groups().get("c1")).hasSize(1);
                    assertThat(outcome.groups().get("c2")).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN max-children-per-key 1 WHEN a parent has two children SHOULD fail only that key")
    void GIVEN_cap_WHEN_parentOverCap_SHOULD_failOnlyThatKey() {
        GroupedBatchLoader loader = new GroupedBatchLoader(
                (keys, md) -> Mono.just(List.of(order("1", "c1"), order("2", "c1"), order("3", "c2"))),
                "customerId",
                1);

        StepVerifier.create(loader.loadGrouped(List.of("c1", "c2"), new Metadata()))
                .assertNext(outcome -> {
                    assertThat(outcome.groups()).containsOnlyKeys("c2");
                    assertThat(outcome.failures()).containsOnlyKeys("c1");
                    assertThat(outcome.failures().get("c1").getMessage()).contains("max-children-per-key (1)");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a failing item source WHEN grouped SHOULD propagate the error")
    void GIVEN_failingSource_WHEN_grouped_SHOULD_propagateError() {
        GroupedBatchLoader loader =
                new GroupedBatchLoader((keys, md) -> Mono.error(new IllegalStateException("down")), "customerId", 0);

        StepVerifier.create(loader.loadGrouped(List.of("c1"), new Metadata()))
                .expectErrorMessage("down")
                .verify();
    }
}
