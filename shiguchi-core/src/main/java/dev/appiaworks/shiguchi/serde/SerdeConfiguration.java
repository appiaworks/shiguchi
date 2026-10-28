package dev.appiaworks.shiguchi.serde;

import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Beans for the serde classes that live in protobuf-java-extras, which knows nothing of Spring
 * and so cannot carry {@code @Component} itself.
 */
@Configuration(proxyBeanMethods = false)
public class SerdeConfiguration {

    /**
     * Tier 0. After the mapping is installed: a program bakes in which types are scalars when it
     * is built.
     */
    @Bean
    @DependsOn(ProtoJsonMappingConfigurer.BEAN_NAME)
    public WireJsonTranscoder wireJsonTranscoder() {
        return new WireJsonTranscoder();
    }
}
