package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GatewayHostedService;
import dev.appiaworks.shiguchi.proto.cursus.PushHooksGrpc;
import dev.appiaworks.shiguchi.proto.cursus.PushPeerGrpc;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;

/**
 * BDD-style unit tests for {@link SpringBeanServiceDiscovery#populateIfEnabled(ServiceDefinition)}.
 *
 * <p>ApplicationContext is mocked to return controlled BindableService beans. The beans are real
 * generated stubs (PushHooks, PushPeer, grpc health), so the schema each one carries is the one
 * protoc produced. Expected results name the generated {@code *Grpc} class via its class literal.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpringBeanServiceDiscovery")
class SpringBeanServiceDiscoveryTest {

    private static final String HOOKS_SERVICE = "shiguchi.cursus.v1.PushHooks";

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
    @DisplayName("GIVEN springBeans=false WHEN populateIfEnabled is called SHOULD not query ApplicationContext")
    void given_springBeansDisabled_when_populateIfEnabled_should_notQueryApplicationContext() {
        serviceDef.getAutoDiscover().setSpringBeans(false);

        discovery.populateIfEnabled(serviceDef);

        verify(applicationContext, never()).getBeansOfType(BindableService.class);
        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Empty ApplicationContext (no BindableService beans)
    // =========================================================================

    @Test
    @DisplayName(
            "GIVEN springBeans=true and no BindableService beans WHEN populateIfEnabled is called SHOULD leave the list empty")
    void given_springBeansEnabledAndNoBindableBeans_when_populateIfEnabled_should_leaveEmptyList() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(new HashMap<>());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // The generated stub is found from the bean's schema, whatever the bean's class
    // =========================================================================

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a top-level subclass of a generated ImplBase WHEN populateIfEnabled is called SHOULD add that generated *Grpc class")
    void given_topLevelSubclassOfGeneratedImplBase_when_populateIfEnabled_should_addGeneratedGrpcClass() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("hooksBean", new HooksBean());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).containsExactly(PushHooksGrpc.class.getName());
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a CGLIB proxy of a generated ImplBase subclass WHEN populateIfEnabled is called SHOULD add that generated *Grpc class")
    void given_cglibProxyOfGeneratedImplBase_when_populateIfEnabled_should_addGeneratedGrpcClass() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        ProxyFactory factory = new ProxyFactory(new HooksBean());
        factory.setProxyTargetClass(true);
        BindableService proxy = (BindableService) factory.getProxy();
        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
        registerBeans("hooksBean", proxy);

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).containsExactly(PushHooksGrpc.class.getName());
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a bean class named MarketHistoryGrpc extending a generated ImplBase WHEN populateIfEnabled is called SHOULD add the real stub class, not the bean class")
    void given_beanClassNamedGrpcExtendingGeneratedImplBase_when_populateIfEnabled_should_addRealStubClass() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("marketHistoryBean", new MarketHistoryGrpc());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses())
                .containsExactly(PushPeerGrpc.class.getName())
                .doesNotContain(MarketHistoryGrpc.class.getName());
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a bean class named *Grpc implementing BindableService with a non-protobuf schema WHEN populateIfEnabled is called SHOULD add nothing")
    void given_grpcNamedBeanWithNonProtobufSchema_when_populateIfEnabled_should_addNothing() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("nonProtoGrpcBean", new NonProtoGrpc());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a fake supplier nested in a class whose getServiceDescriptor describes another service WHEN populateIfEnabled is called SHOULD add nothing")
    void given_fakeSupplierWhoseOuterClaimsAnotherService_when_populateIfEnabled_should_addNothing() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("imposterBean", beanFor(HOOKS_SERVICE, new ImposterGrpc.Supplier()));

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a fake supplier nested in a class without getServiceDescriptor WHEN populateIfEnabled is called SHOULD add nothing")
    void given_fakeSupplierWithoutStaticDescriptorMethod_when_populateIfEnabled_should_addNothing() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("bareImposterBean", beanFor(HOOKS_SERVICE, new BareImposterGrpc.Supplier()));

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Services that are not part of the application's API
    // =========================================================================

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a gRPC health service bean WHEN populateIfEnabled is called SHOULD skip it and add nothing")
    void given_healthServiceBean_when_populateIfEnabled_should_addNothing() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("healthBean", new HealthBean());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a gateway-hosted bean over a generated ImplBase WHEN populateIfEnabled is called SHOULD skip it and add nothing")
    void given_gatewayHostedBean_when_populateIfEnabled_should_addNothing() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("hostedHooksBean", new HostedHooks());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Several beans, one class; retries
    // =========================================================================

    @Test
    @DisplayName(
            "GIVEN springBeans=true and two beans of the same generated service WHEN populateIfEnabled is called SHOULD list its class once")
    void given_twoBeansOfSameGeneratedService_when_populateIfEnabled_should_listClassOnce() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("hooksBean", new HooksBean(), "hooksBeanReplica", new HooksBean());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).containsExactly(PushHooksGrpc.class.getName());
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and the generated class already listed by an earlier pass WHEN populateIfEnabled runs again SHOULD leave the list unchanged")
    void given_classAlreadyListed_when_populateIfEnabledRunsAgain_should_leaveListUnchanged() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        serviceDef.getStaticServiceClasses().add(PushHooksGrpc.class.getName());
        registerBeans("hooksBean", new HooksBean());

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).containsExactly(PushHooksGrpc.class.getName());
    }

    // =========================================================================
    // Unresolvable descriptors
    // =========================================================================

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a bean whose ServerServiceDefinition has no ServiceDescriptor WHEN populateIfEnabled is called SHOULD skip that bean")
    void given_nullServiceDescriptor_when_populateIfEnabled_should_skipBean() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        BindableService beanWithNullDescriptor = new BindableService() {
            @Override
            public ServerServiceDefinition bindService() {
                return ServerServiceDefinition.builder("com.example.NullDescriptorService")
                        .build();
            }
        };
        registerBeans("nullDescriptorBean", beanWithNullDescriptor);

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN springBeans=true and a bean whose schema descriptor is not ProtoServiceDescriptorSupplier WHEN populateIfEnabled is called SHOULD skip that bean")
    void given_nonProtoSchemaDescriptor_when_populateIfEnabled_should_skipBean() {
        serviceDef.getAutoDiscover().setSpringBeans(true);
        registerBeans("nonProtoSchemaBean", beanFor(HOOKS_SERVICE, new Object()));

        discovery.populateIfEnabled(serviceDef);

        assertThat(serviceDef.getStaticServiceClasses()).isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void registerBeans(Object... nameAndBeanPairs) {
        Map<String, BindableService> beans = new HashMap<>();
        for (int i = 0; i < nameAndBeanPairs.length; i += 2) {
            beans.put((String) nameAndBeanPairs[i], (BindableService) nameAndBeanPairs[i + 1]);
        }
        when(applicationContext.getBeansOfType(BindableService.class)).thenReturn(beans);
    }

    /** A bean whose service descriptor is the given service name with the given schema. */
    private static BindableService beanFor(String serviceName, Object schema) {
        ServiceDescriptor descriptor = ServiceDescriptor.newBuilder(serviceName)
                .setSchemaDescriptor(schema)
                .build();
        return new BindableService() {
            @Override
            public ServerServiceDefinition bindService() {
                return ServerServiceDefinition.builder(descriptor).build();
            }
        };
    }

    /** Top-level-style subclass of the generated PushHooks ImplBase: its own class is not a *Grpc class. */
    static class HooksBean extends PushHooksGrpc.PushHooksImplBase {}

    /** A bean class whose name ends in Grpc but which is not a generated class. */
    static class MarketHistoryGrpc extends PushPeerGrpc.PushPeerImplBase {}

    /** A BindableService named like a generated class, with a schema that is not protobuf. */
    static class NonProtoGrpc implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            ServiceDescriptor descriptor = ServiceDescriptor.newBuilder(HOOKS_SERVICE)
                    .setSchemaDescriptor("not a protobuf schema")
                    .build();
            return ServerServiceDefinition.builder(descriptor).build();
        }
    }

    /** Impersonates a generated class: its static getServiceDescriptor describes another service. */
    static class ImposterGrpc {
        public static ServiceDescriptor getServiceDescriptor() {
            return ServiceDescriptor.newBuilder("other.pkg.Service")
                    .setSchemaDescriptor(new Supplier())
                    .build();
        }

        static final class Supplier implements ProtoServiceDescriptorSupplier {
            @Override
            public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
                return null; // Not used by discovery
            }

            @Override
            public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
                return null; // Not used by discovery
            }
        }
    }

    /** Impersonates a generated class that has no static getServiceDescriptor at all. */
    static class BareImposterGrpc {
        static final class Supplier implements ProtoServiceDescriptorSupplier {
            @Override
            public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
                return null; // Not used by discovery
            }

            @Override
            public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
                return null; // Not used by discovery
            }
        }
    }

    /** The gRPC health service an application registers as a bean. */
    static class HealthBean extends HealthGrpc.HealthImplBase {}

    /** A service the gateway hosts itself, implemented over a generated ImplBase. */
    static class HostedHooks extends PushHooksGrpc.PushHooksImplBase implements GatewayHostedService {}
}
