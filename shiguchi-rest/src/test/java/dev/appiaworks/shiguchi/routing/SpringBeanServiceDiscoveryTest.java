package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

/**
 * BDD-style unit tests for {@link SpringBeanServiceDiscovery#populateIfEnabled(ServiceDefinition)}.
 *
 * <p>ApplicationContext is mocked to return controlled BindableService beans.
 * Tests verify that gRPC stub class names are correctly derived and added to staticServiceClasses.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpringBeanServiceDiscovery")
class SpringBeanServiceDiscoveryTest {

    @Mock
    private ApplicationContext applicationContext;

    private SpringBeanServiceDiscovery discovery;
    private ServiceDefinition serviceDef;

    @BeforeEach
    void setUp() {
        discovery = new SpringBeanServiceDiscovery(applicationContext);
        serviceDef = new ServiceDefinition();
        serviceDef.setName("test-service");
        serviceDef.getAutoDiscover().setSpringBeans(false); // Default to disabled
    }

    // =========================================================================
    // Early exit when springBeans = false
    // =========================================================================

    @Test
    @DisplayName("Given springBeans=false, when populateIfEnabled is called, should not query ApplicationContext")
    void given_springBeansDisabled_when_populateIfEnabled_should_notQueryApplicationContext() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(false);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        verify(applicationContext, never()).getBeansOfType(BindableService.class);
        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Empty ApplicationContext (no BindableService beans)
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and ApplicationContext has no BindableService beans, when populateIfEnabled is called, should leave staticServiceClasses empty")
    void given_springBeansEnabledAndNoBindableBeans_when_populateIfEnabled_should_leaveEmptyList() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(new HashMap<>()); // Empty map

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Bean class is inner class of *Grpc outer class
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and bean class is inner class of *Grpc outer class, when populateIfEnabled is called, should add outer *Grpc class name")
    void given_springBeansEnabledAndInnerClassOfGrpcOuter_when_populateIfEnabled_should_addOuterGrpcClassName() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);

        FakeGrpc.FakeServiceImplBase bean = new FakeGrpc.FakeServiceImplBase();
        Map<String, BindableService> beans = new HashMap<>();
        beans.put("fakeServiceBean", bean);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses())
                .containsExactly("dev.appiaworks.shiguchi.routing.SpringBeanServiceDiscoveryTest$FakeGrpc");
    }

    // =========================================================================
    // Bean class name itself ends with "Grpc"
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and bean class name itself ends with 'Grpc', when populateIfEnabled is called, should add that class name")
    void given_springBeansEnabledAndBeanNameEndsWithGrpc_when_populateIfEnabled_should_addBeanClassName() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);

        StandaloneGrpc bean = new StandaloneGrpc();
        Map<String, BindableService> beans = new HashMap<>();
        beans.put("standaloneGrpcBean", bean);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses())
                .containsExactly("dev.appiaworks.shiguchi.routing.SpringBeanServiceDiscoveryTest$StandaloneGrpc");
    }

    // =========================================================================
    // ServiceDescriptor is null
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and bean's ServerServiceDefinition returns null ServiceDescriptor, when populateIfEnabled is called, should skip that bean")
    void given_springBeansEnabledAndNullServiceDescriptor_when_populateIfEnabled_should_skipBean() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);

        // Bean returns ServerServiceDefinition with null ServiceDescriptor
        BindableService beanWithNullDescriptor = new BindableService() {
            @Override
            public ServerServiceDefinition bindService() {
                return ServerServiceDefinition.builder("com.example.NullDescriptorService")
                        .build();
            }
        };

        Map<String, BindableService> beans = new HashMap<>();
        beans.put("nullDescriptorBean", beanWithNullDescriptor);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Schema descriptor is not ProtoServiceDescriptorSupplier
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and bean's schema descriptor is not ProtoServiceDescriptorSupplier, when populateIfEnabled is called, should skip that bean")
    void given_springBeansEnabledAndNonProtoSchemaDescriptor_when_populateIfEnabled_should_skipBean() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);

        // Mock ServiceDescriptor with non-ProtoServiceDescriptorSupplier schema
        ServiceDescriptor mockDescriptor = mockServiceDescriptorWithCustomSchema();

        BindableService beanWithNonProtoSchema = createBindableServiceWithDescriptor(mockDescriptor);

        Map<String, BindableService> beans = new HashMap<>();
        beans.put("nonProtoSchemaBean", beanWithNonProtoSchema);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Duplicate class detection
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and a class already in staticServiceClasses, when same class discovered again, should not add duplicate")
    void given_springBeansEnabledAndClassAlreadyStatic_when_populateIfEnabled_should_notAddDuplicate() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);
        String grpcClassName = "dev.appiaworks.shiguchi.routing.SpringBeanServiceDiscoveryTest$FakeGrpc";
        serviceDef.getStaticServiceClasses().add(grpcClassName); // Pre-populate

        FakeGrpc.FakeServiceImplBase bean = new FakeGrpc.FakeServiceImplBase();
        Map<String, BindableService> beans = new HashMap<>();
        beans.put("fakeServiceBean", bean);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses()).hasSize(1).containsExactly(grpcClassName);
    }

    // =========================================================================
    // Class name does not end with "Grpc" and no enclosing class
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and bean class name does not end with 'Grpc' and has no enclosing class, when populateIfEnabled is called, should skip that bean")
    void given_springBeansEnabledAndUnrecognizedClassName_when_populateIfEnabled_should_skipBean() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);

        NotAGrpcService bean = new NotAGrpcService();
        Map<String, BindableService> beans = new HashMap<>();
        beans.put("notAGrpcBean", bean);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Multiple beans: some valid, some skipped
    // =========================================================================

    @Test
    @DisplayName(
            "Given springBeans=true and multiple beans with mixed validity, when populateIfEnabled is called, should add only valid gRPC class names")
    void given_springBeansEnabledAndMixedBeans_when_populateIfEnabled_should_addOnlyValidBeans() {
        // Arrange
        serviceDef.getAutoDiscover().setSpringBeans(true);

        Map<String, BindableService> beans = new HashMap<>();
        beans.put("fakeServiceBean", new FakeGrpc.FakeServiceImplBase()); // Valid: inner class of *Grpc
        beans.put("standaloneGrpcBean", new StandaloneGrpc()); // Valid: class name ends with Grpc
        beans.put("notAGrpcBean", new NotAGrpcService()); // Skip: not a Grpc class
        beans.put("nullDescriptorBean", new BindableService() {
            @Override
            public ServerServiceDefinition bindService() {
                return ServerServiceDefinition.builder("com.example.Service").build();
            }
        }); // Skip: null ServiceDescriptor

        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);

        // Act
        discovery.populateIfEnabled(serviceDef);

        // Assert
        assertThat(serviceDef.getStaticServiceClasses())
                .hasSize(2)
                .contains(
                        "dev.appiaworks.shiguchi.routing.SpringBeanServiceDiscoveryTest$FakeGrpc",
                        "dev.appiaworks.shiguchi.routing.SpringBeanServiceDiscoveryTest$StandaloneGrpc");
    }

    // =========================================================================
    // Helpers for test double implementations
    // =========================================================================

    /**
     * Fake outer *Grpc class with inner *ImplBase.
     * Mimics the structure generated by protoc for gRPC stubs.
     */
    static class FakeGrpc {
        static class FakeServiceImplBase implements BindableService {
            @Override
            public ServerServiceDefinition bindService() {
                ServiceDescriptor descriptor = createMockDescriptorWithSchema(new FakeProtoServiceDescriptorSupplier());
                ServerServiceDefinition def = mock(ServerServiceDefinition.class);
                when(def.getServiceDescriptor()).thenReturn(descriptor);
                return def;
            }
        }
    }

    /**
     * Standalone BindableService class that ends with "Grpc" (unusual, but valid case).
     */
    static class StandaloneGrpc implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            ServiceDescriptor descriptor = createMockDescriptorWithSchema(new FakeProtoServiceDescriptorSupplier());
            ServerServiceDefinition def = mock(ServerServiceDefinition.class);
            when(def.getServiceDescriptor()).thenReturn(descriptor);
            return def;
        }
    }

    /**
     * BindableService that does not end with "Grpc" — should be skipped during discovery.
     */
    static class NotAGrpcService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            ServiceDescriptor descriptor = createMockDescriptorWithSchema(new FakeProtoServiceDescriptorSupplier());
            ServerServiceDefinition def = mock(ServerServiceDefinition.class);
            when(def.getServiceDescriptor()).thenReturn(descriptor);
            return def;
        }
    }

    /**
     * Minimal implementation of ProtoServiceDescriptorSupplier for testing.
     */
    static class FakeProtoServiceDescriptorSupplier implements ProtoServiceDescriptorSupplier {
        @Override
        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
            return null; // Not used in these tests
        }

        @Override
        public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
            return null; // Not used in these tests
        }
    }

    /**
     * Helper to create a mock ServiceDescriptor with a specific schema descriptor.
     */
    private static ServiceDescriptor createMockDescriptorWithSchema(Object schemaDescriptor) {
        ServiceDescriptor descriptor = mock(ServiceDescriptor.class);
        when(descriptor.getSchemaDescriptor()).thenReturn(schemaDescriptor);
        return descriptor;
    }

    /**
     * Helper to create a BindableService with a specific ServiceDescriptor.
     */
    private static BindableService createBindableServiceWithDescriptor(ServiceDescriptor descriptor) {
        return new BindableService() {
            @Override
            public ServerServiceDefinition bindService() {
                ServerServiceDefinition def = mock(ServerServiceDefinition.class);
                when(def.getServiceDescriptor()).thenReturn(descriptor);
                return def;
            }
        };
    }

    /**
     * Helper to create a ServiceDescriptor with a non-ProtoServiceDescriptorSupplier schema.
     */
    private static ServiceDescriptor mockServiceDescriptorWithCustomSchema() {
        Object customSchema = new Object(); // Not an instanceof ProtoServiceDescriptorSupplier
        return createMockDescriptorWithSchema(customSchema);
    }
}
