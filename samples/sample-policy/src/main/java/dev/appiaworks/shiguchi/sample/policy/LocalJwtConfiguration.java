package dev.appiaworks.shiguchi.sample.policy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * A JWT decoder backed by a fixed HMAC secret, so the sample has callers with scopes without an
 * identity provider. A real gateway sets {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * instead. The secret is public on purpose: this is a sample.
 */
@Configuration
public class LocalJwtConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LocalJwtConfiguration.class);

    /** Must be at least 256 bits for HS256. */
    static final String SECRET = "sample-policy-development-secret-key-at-least-256-bits";

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        var key = new SecretKeySpec(SECRET.getBytes(), "HmacSHA256");
        log.info("demo token (no scope): {}", mintToken("user", List.of()));
        log.info("demo token (readonly): {}", mintToken("viewer", List.of("readonly")));
        log.info("demo token (admin):    {}", mintToken("root", List.of("admin")));
        return NimbusReactiveJwtDecoder.withSecretKey(key).build();
    }

    /** Mints a token whose {@code scope} claim becomes the authorities {@code SCOPE_<scope>}. */
    static String mintToken(String subject, List<String> scopes) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer("sample-policy")
                    .claim("scope", String.join(" ", scopes))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
