package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.proto.api.AuthzRule;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import io.grpc.MethodDescriptor.MethodType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for {@link ProtoAccessRules}.
 *
 * <p>Method descriptors are built programmatically from {@code DescriptorProtos} so that the
 * {@code (shiguchi.api.method)} option is read exactly as it would be from a compiled proto file.
 */
@DisplayName("ProtoAccessRules")
class ProtoAccessRulesTest {

    private static final String SERVICE_FQN = "pkg.Svc";
    private static final String METHOD_NAME = "M";

    // -------------------------------------------------------------------------
    // Descriptor building helpers
    // -------------------------------------------------------------------------

    /** Returns a method descriptor carrying the given {@code (shiguchi.api.method)} option, or none when null. */
    private static MethodDescriptor methodWithSchema(MethodRule schema) throws Exception {
        MethodDescriptorProto.Builder method = MethodDescriptorProto.newBuilder()
                .setName(METHOD_NAME)
                .setInputType(".Empty")
                .setOutputType(".Empty");
        if (schema != null) {
            method.setOptions(MethodOptions.newBuilder()
                    .setExtension(MethodProto.method, schema)
                    .build());
        }

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("access-" + System.nanoTime() + ".proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Empty")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)
                                .setLabel(Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Svc")
                        .addMethod(method.build())
                        .build())
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        return fd.findServiceByName("Svc").findMethodByName(METHOD_NAME);
    }

    private static RouteDefinition.Builder newRouteBuilder() {
        return RouteDefinition.builder(SERVICE_FQN, METHOD_NAME, MethodType.UNARY, "localhost:9090");
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a method with no (shiguchi.api.method) option WHEN apply is called SHOULD leave the route with no auth scopes and no authz rules")
    void GIVEN_methodWithNoOptions_WHEN_apply_SHOULD_leaveRouteWithoutAccessRules() throws Exception {
        // Arrange
        MethodDescriptor md = methodWithSchema(null);

        // Act
        RouteDefinition route = ProtoAccessRules.apply(newRouteBuilder(), md).build();

        // Assert
        assertThat(route.getAuthScopes()).isEmpty();
        assertThat(route.getAuthzRules()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN an annotation declaring auth_scopes a and b without a field name WHEN apply is called SHOULD put exactly scopes a and b on the route")
    void GIVEN_annotationWithAuthScopesAndNoName_WHEN_apply_SHOULD_putExactlyTheScopesOnRoute() throws Exception {
        // Arrange
        MethodRule schema =
                MethodRule.newBuilder().addAuthScopes("a").addAuthScopes("b").build();
        MethodDescriptor md = methodWithSchema(schema);

        // Act
        RouteDefinition route = ProtoAccessRules.apply(newRouteBuilder(), md).build();

        // Assert
        assertThat(route.getAuthScopes()).containsExactly("a", "b");
    }

    @Test
    @DisplayName(
            "GIVEN an annotation with one complete authz rule WHEN apply is called SHOULD keep that rule on the route")
    void GIVEN_annotationWithCompleteAuthzRule_WHEN_apply_SHOULD_keepRuleOnRoute() throws Exception {
        // Arrange
        MethodRule schema = MethodRule.newBuilder()
                .addAuthz(AuthzRule.newBuilder()
                        .setObjectType("document")
                        .setRelation("viewer")
                        .setIdField("id"))
                .build();
        MethodDescriptor md = methodWithSchema(schema);

        // Act
        RouteDefinition route = ProtoAccessRules.apply(newRouteBuilder(), md).build();

        // Assert
        assertThat(route.getAuthzRules()).containsExactly(new AuthzRuleDefinition("document", "viewer", "id"));
    }

    @Test
    @DisplayName(
            "GIVEN an annotation with one complete and one incomplete authz rule WHEN apply is called SHOULD keep only the complete rule")
    void GIVEN_annotationWithCompleteAndIncompleteAuthzRules_WHEN_apply_SHOULD_keepOnlyCompleteRule() throws Exception {
        // Arrange
        MethodRule schema = MethodRule.newBuilder()
                .addAuthz(AuthzRule.newBuilder()
                        .setObjectType("document")
                        .setRelation("viewer")
                        .setIdField("id"))
                .addAuthz(AuthzRule.newBuilder()
                        .setObjectType("document")
                        .setRelation("")
                        .setIdField("id"))
                .build();
        MethodDescriptor md = methodWithSchema(schema);

        // Act
        RouteDefinition route = ProtoAccessRules.apply(newRouteBuilder(), md).build();

        // Assert
        assertThat(route.getAuthzRules()).containsExactly(new AuthzRuleDefinition("document", "viewer", "id"));
    }

    @Test
    @DisplayName(
            "GIVEN a route builder and a method WHEN apply is called SHOULD return the same builder instance it was given")
    void GIVEN_routeBuilderAndMethod_WHEN_apply_SHOULD_returnSameBuilderInstance() throws Exception {
        // Arrange
        RouteDefinition.Builder builder = newRouteBuilder();
        MethodDescriptor md =
                methodWithSchema(MethodRule.newBuilder().addAuthScopes("a").build());

        // Act
        RouteDefinition.Builder result = ProtoAccessRules.apply(builder, md);

        // Assert
        assertThat(result).isSameAs(builder);
    }
}
