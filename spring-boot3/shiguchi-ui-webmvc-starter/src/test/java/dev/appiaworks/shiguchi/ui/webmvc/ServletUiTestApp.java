package dev.appiaworks.shiguchi.ui.webmvc;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A minimal servlet application standing in for the host: Spring Boot auto-configuration (so the
 * UI starter's own auto-configuration is what registers the UI) and a security chain the host
 * owns. Under {@code dev.appiaworks.shiguchi}, but a {@code @SpringBootConfiguration}, so the UI
 * starter's component scan leaves it out.
 *
 * <p>The chain permits every request and accepts HTTP Basic, so a test is anonymous unless it
 * sends credentials ({@code alice} / {@code secret}).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class ServletUiTestApp {

    @Bean
    SecurityFilterChain hostSecurity(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService hostUsers() {
        return new InMemoryUserDetailsManager(User.withUsername("alice")
                .password("{noop}secret")
                .roles("USER")
                .build());
    }
}
