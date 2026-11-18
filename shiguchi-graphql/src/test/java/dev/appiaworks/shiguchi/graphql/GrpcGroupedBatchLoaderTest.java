package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcGroupedBatchLoader")
class GrpcGroupedBatchLoaderTest {

    @Mock
    private ReactiveGrpcDispatcher dispatcher;

    @Mock
    private RouteDefinition route;

    @Mock
    private ServiceEndpointContext ctx;

    @BeforeEach
    void stubRoute() {
        when(route.getGraphQlName()).thenReturn("booksByAuthors");
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.SERVER_STREAMING);
    }

    private GrpcGroupedBatchLoader loader(int cap) {
        return new GrpcGroupedBatchLoader(
                new GrpcBatchLoader(route, ctx, dispatcher, "authorIds", "", "id"), "authorId", cap);
    }

    private static Map<String, Object> book(String id, String authorId) {
        return Map.of("id", id, "authorId", authorId, "title", "t" + id);
    }

    @Test
    @DisplayName("GIVEN books of two authors WHEN grouped SHOULD keep upstream order within each author")
    void given_books_when_grouped_should_keepUpstreamOrderPerKey() {
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(book("3", "a1"), book("1", "a2"), book("2", "a1")));

        StepVerifier.create(loader(0).loadGrouped(List.of("a1", "a2"), new Metadata()))
                .assertNext(outcome -> {
                    assertThat(outcome.groups().get("a1"))
                            .extracting(b -> b.get("id"))
                            .containsExactly("3", "2");
                    assertThat(outcome.groups().get("a2"))
                            .extracting(b -> b.get("id"))
                            .containsExactly("1");
                    assertThat(outcome.hasFailures()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN an author without books WHEN grouped SHOULD map the key to an empty list")
    void given_authorWithoutBooks_when_grouped_should_mapToEmptyList() {
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(book("1", "a1")));

        StepVerifier.create(loader(0).loadGrouped(List.of("a1", "a2"), new Metadata()))
                .assertNext(outcome ->
                        assertThat(outcome.groups().get("a2")).isNotNull().isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a key over max-children-per-key WHEN grouped SHOULD fail that key and load the others")
    void given_keyOverCap_when_grouped_should_failOnlyThatKey() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(book("1", "a1"));
        items.add(book("2", "a1"));
        items.add(book("3", "a1"));
        items.add(book("4", "a2"));
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.fromIterable(items));

        StepVerifier.create(loader(2).loadGrouped(List.of("a1", "a2"), new Metadata()))
                .assertNext(outcome -> {
                    assertThat(outcome.groups()).containsOnlyKeys("a2");
                    assertThat(outcome.failures()).containsOnlyKeys("a1");
                    assertThat(outcome.failures().get("a1").getMessage()).contains("max-children-per-key");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a book of an author nobody asked for WHEN grouped SHOULD drop it")
    void given_foreignParent_when_grouped_should_dropIt() {
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(book("1", "zz")));

        StepVerifier.create(loader(0).loadGrouped(List.of("a1"), new Metadata()))
                .assertNext(outcome -> assertThat(outcome.groups()).containsOnlyKeys("a1"))
                .verifyComplete();
        verify(dispatcher, times(1)).dispatchStreamingToMap(eq(route), eq(ctx), any(), any());
    }
}
