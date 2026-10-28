package dev.appiaworks.shiguchi.graphql.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.graphql.GraphQlContributor;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for PushGraphQlContributor: which namespaces become subscription fields,
 * how their names and payload types are resolved, and what the contributor writes into the SDL
 * and the code registry.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushGraphQlContributor")
class PushGraphQlContributorTest {

    @Mock
    private GrpcRouteRegistry registry;

    @Mock
    private ServiceEndpointContext context;

    private PushProperties push;

    @BeforeEach
    void setUp() {
        push = new PushProperties();
    }

    private PushGraphQlContributor contributor() {
        return new PushGraphQlContributor(push, null, registry, null);
    }

    private static PushProperties.Namespace namespace(String payloadType) {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setPayloadType(payloadType);
        return ns;
    }

    /** A proto3 file with {@code ev.OrderEvent { string order_id = 1; string status = 2; }}. */
    private static FileDescriptor orderEventFile() throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("ev.proto")
                .setPackage("ev")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("OrderEvent")
                        .addField(stringField("order_id", 1))
                        .addField(stringField("status", 2)))
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
    }

    private static FieldDescriptorProto.Builder stringField(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    /** Stubs the registry so that the given file's messages are served by one context. */
    private void serveFile(FileDescriptor file) {
        when(registry.allContexts()).thenReturn(List.of(context));
        when(context.getFileDescriptors()).thenReturn(Map.of("ev.proto", file));
    }

    /** Records what the contributor writes into the SDL; objectType answers the message's simple name. */
    private static final class RecordingSchema implements GraphQlContributor.Schema {
        record TypeDef(String name, String sdl) {}

        final List<String> subscriptionFields = new ArrayList<>();
        final List<TypeDef> types = new ArrayList<>();
        final List<String> objectTypes = new ArrayList<>();

        @Override
        public void subscriptionField(String declaration) {
            subscriptionFields.add(declaration);
        }

        @Override
        public String objectType(Descriptor descriptor) {
            objectTypes.add(descriptor.getFullName());
            return descriptor.getName();
        }

        @Override
        public void type(String name, String sdl) {
            types.add(new TypeDef(name, sdl));
        }

        List<String> typeNames() {
            return types.stream().map(TypeDef::name).toList();
        }

        String sdlOf(String name) {
            return types.stream()
                    .filter(t -> t.name().equals(name))
                    .map(TypeDef::sdl)
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a namespace without overrides WHEN fields are resolved SHOULD default the field and envelope names")
    void given_namespaceWithoutOverrides_when_fieldsResolved_should_defaultFieldAndEnvelopeNames() {
        push.getNamespaces().put("orders", namespace(""));

        List<PushGraphQlContributor.Field> fields = contributor().fields();

        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).name()).isEqualTo("ordersEvents");
        assertThat(fields.get(0).envelope()).isEqualTo("OrdersEventsEnvelope");
        assertThat(fields.get(0).payload()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a namespace with a graphql-field override WHEN fields are resolved SHOULD use the override for field and envelope")
    void given_graphqlFieldOverride_when_fieldsResolved_should_useOverrideForFieldAndEnvelope() {
        PushProperties.Namespace ns = namespace("");
        ns.setGraphqlField("orderFeed");
        push.getNamespaces().put("orders", ns);

        List<PushGraphQlContributor.Field> fields = contributor().fields();

        assertThat(fields).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("orderFeed");
            assertThat(f.envelope()).isEqualTo("OrderFeedEnvelope");
        });
    }

    @Test
    @DisplayName("GIVEN a namespace with GraphQL disabled WHEN fields are resolved SHOULD leave it out")
    void given_namespaceWithGraphqlDisabled_when_fieldsResolved_should_skipIt() {
        PushProperties.Namespace ns = namespace("");
        ns.setGraphql(false);
        push.getNamespaces().put("audit", ns);

        assertThat(contributor().fields()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN two namespaces resolving to the same field name WHEN fields are resolved SHOULD fail naming graphql-field")
    void given_twoNamespacesWithSameFieldName_when_fieldsResolved_should_failNamingGraphqlField() {
        PushProperties.Namespace first = namespace("");
        first.setGraphqlField("feed");
        PushProperties.Namespace second = namespace("");
        second.setGraphqlField("feed");
        push.getNamespaces().put("orders", first);
        push.getNamespaces().put("orderz", second);

        PushGraphQlContributor contributor = contributor();
        assertThatThrownBy(contributor::fields)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("graphql-field");
    }

    @Test
    @DisplayName(
            "GIVEN a payload-type served by a registered context WHEN fields are resolved SHOULD resolve the descriptor and its context")
    void given_payloadTypeServedByContext_when_fieldsResolved_should_resolveDescriptorAndContext() throws Exception {
        serveFile(orderEventFile());
        push.getNamespaces().put("orders", namespace("ev.OrderEvent"));

        List<PushGraphQlContributor.Field> fields = contributor().fields();

        assertThat(fields.get(0).payload().getFullName()).isEqualTo("ev.OrderEvent");
        assertThat(fields.get(0).context()).isSameAs(context);
    }

    @Test
    @DisplayName("GIVEN a payload-type no served message has WHEN fields are resolved SHOULD fail naming payload-type")
    void given_unknownPayloadType_when_fieldsResolved_should_failNamingPayloadType() {
        push.getNamespaces().put("orders", namespace("ev.Missing"));

        PushGraphQlContributor contributor = contributor();
        assertThatThrownBy(contributor::fields)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload-type");
    }

    @Test
    @DisplayName(
            "GIVEN a typed and an untyped namespace WHEN the schema is contributed SHOULD emit the attribute type, envelopes and subscription fields")
    void given_typedAndUntypedNamespaces_when_schemaContributed_should_emitAttributeTypeEnvelopesAndSubscriptionFields()
            throws Exception {
        serveFile(orderEventFile());
        push.getNamespaces().put("orders", namespace("ev.OrderEvent"));
        push.getNamespaces().put("audit", namespace(""));
        RecordingSchema schema = new RecordingSchema();

        contributor().contributeSchema(schema);

        assertThat(Collections.frequency(schema.typeNames(), "PushAttribute")).isEqualTo(1);
        assertThat(schema.sdlOf("OrdersEventsEnvelope")).contains("  payload: OrderEvent\n");
        assertThat(schema.sdlOf("AuditEventsEnvelope")).contains("  payload: JSON\n");
        assertThat(schema.subscriptionFields)
                .containsExactlyInAnyOrder(
                        "ordersEvents(topic: String!, after: ID, origin: String): OrdersEventsEnvelope",
                        "auditEvents(topic: String!, after: ID, origin: String): AuditEventsEnvelope");
    }

    @Test
    @DisplayName(
            "GIVEN a namespace with GraphQL enabled WHEN data fetchers are contributed SHOULD register a fetcher on the Subscription field")
    void given_namespaceWithGraphqlEnabled_when_dataFetchersContributed_should_registerFetcherOnSubscriptionField() {
        push.getNamespaces().put("orders", namespace(""));
        GraphQLCodeRegistry.Builder builder = spy(GraphQLCodeRegistry.newCodeRegistry());

        contributor().contributeDataFetchers(builder);

        verify(builder)
                .dataFetcher(eq(FieldCoordinates.coordinates("Subscription", "ordersEvents")), any(DataFetcher.class));
    }
}
