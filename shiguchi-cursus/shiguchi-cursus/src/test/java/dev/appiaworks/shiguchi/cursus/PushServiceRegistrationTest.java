package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Hosted push service registration")
class PushServiceRegistrationTest {

    // ---- definition() tests ----

    @Test
    @DisplayName("with default settings the hosted push service is a static in-process service under /push")
    void given_default_settings_when_building_definition_should_describe_static_in_process_service() {
        PushServiceRegistration registration = new PushServiceRegistration(new MockEnvironment());

        ServiceDefinition def = registration.definition();

        assertThat(def.getName()).isEqualTo("push");
        assertThat(def.getMode()).isEqualTo(GatewayMode.STATIC);
        assertThat(def.getTarget()).isEqualTo("inprocess:" + registration.inProcessName());
        assertThat(def.getStaticServiceClasses()).containsExactly(PushServiceGrpc.class.getName());
        assertThat(def.getPathPrefix()).isEqualTo("/push");
        assertThat(def.getDeadline()).isEqualTo(Duration.ZERO);
        assertThat(def.isInsecure()).isTrue();
    }

    @Test
    @DisplayName(
            "with a configured service name and path the service takes those, with the path trailing slash removed")
    void given_configured_service_name_and_path_when_building_definition_should_use_them() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("shiguchi.cursus.grpc.service-name", "events")
                .withProperty("shiguchi.cursus.path", "/live/");

        ServiceDefinition def = new PushServiceRegistration(environment).definition();

        assertThat(def.getName()).isEqualTo("events");
        assertThat(def.getPathPrefix()).isEqualTo("/live");
    }

    // ---- inProcessName() tests ----

    @Test
    @DisplayName("each registration gets its own in-process server name with the push prefix")
    void given_two_registrations_when_naming_in_process_server_should_be_unique_and_prefixed() {
        PushServiceRegistration first = new PushServiceRegistration(new MockEnvironment());
        PushServiceRegistration second = new PushServiceRegistration(new MockEnvironment());

        assertThat(first.inProcessName()).startsWith("shiguchi-cursus-");
        assertThat(second.inProcessName()).startsWith("shiguchi-cursus-");
        assertThat(first.inProcessName()).isNotEqualTo(second.inProcessName());
    }

    // ---- postProcessAfterInitialization() tests ----

    @Test
    @DisplayName("when gateway properties are initialised the hosted service is appended once and the bean is returned")
    void given_gateway_properties_when_post_processed_should_append_one_service_and_return_same_bean() {
        PushServiceRegistration registration = new PushServiceRegistration(new MockEnvironment());
        GatewayProperties properties = new GatewayProperties();

        Object result = registration.postProcessAfterInitialization(properties, "gatewayProperties");

        assertThat(result).isSameAs(properties);
        assertThat(properties.getServices()).hasSize(1);
        assertThat(properties.getServices().get(0).getName()).isEqualTo("push");
    }

    @Test
    @DisplayName("when the application already declares a service named push the hosted one is not added")
    void given_service_already_named_push_when_post_processed_should_not_add_another() {
        PushServiceRegistration registration = new PushServiceRegistration(new MockEnvironment());
        GatewayProperties properties = new GatewayProperties();
        ServiceDefinition declared = new ServiceDefinition();
        declared.setName("push");
        properties.getServices().add(declared);

        registration.postProcessAfterInitialization(properties, "gatewayProperties");

        assertThat(properties.getServices()).hasSize(1);
        assertThat(properties.getServices().get(0)).isSameAs(declared);
    }

    @Test
    @DisplayName("when an unrelated bean is post-processed it is returned unchanged")
    void given_unrelated_bean_when_post_processed_should_return_it_unchanged() {
        PushServiceRegistration registration = new PushServiceRegistration(new MockEnvironment());
        String bean = "not gateway properties";

        Object result = registration.postProcessAfterInitialization(bean, "someBean");

        assertThat(result).isSameAs(bean);
    }
}
