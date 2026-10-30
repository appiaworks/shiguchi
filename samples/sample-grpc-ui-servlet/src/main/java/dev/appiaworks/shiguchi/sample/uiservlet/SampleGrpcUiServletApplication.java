package dev.appiaworks.shiguchi.sample.uiservlet;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Spring MVC application with the gRPC web console embedded: its own {@code @RestController}
 * keeps working, and the console mounts next to it.
 *
 * <p>Open: http://localhost:8080/grpc-ui (and http://localhost:8080/hello for the app's own
 * endpoint).
 */
@SpringBootApplication
public class SampleGrpcUiServletApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleGrpcUiServletApplication.class, args);
    }

    @RestController
    static class HelloController {
        @GetMapping("/hello")
        Map<String, String> hello() {
            return Map.of("message", "hello from Spring MVC");
        }
    }
}
