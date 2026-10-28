package dev.appiaworks.shiguchi.sample.validation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A gateway that enforces the protovalidate constraints declared in the protos, rejecting invalid
 * requests before they reach the backend.
 *
 * <p>The constraints live next to the fields they constrain, so the rules are written once and
 * both the gateway and the backend can read them. The gateway turns a violation into an HTTP 400
 * with the offending field paths, which saves a round trip and gives the caller a better error
 * than a generic {@code INVALID_ARGUMENT} from somewhere deeper.
 */
@SpringBootApplication
public class SampleValidationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleValidationApplication.class, args);
    }
}
