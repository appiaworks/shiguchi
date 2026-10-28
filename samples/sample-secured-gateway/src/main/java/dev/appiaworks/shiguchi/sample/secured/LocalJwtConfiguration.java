package dev.appiaworks.shiguchi.sample.secured;

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
 * A JWT decoder backed by a fixed HMAC secret, so the sample authenticates real tokens without an
 * identity provider to stand up.
 *
 * <p>A real gateway sets {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} and Spring
 * builds the decoder from the provider's JWKS. Nothing else in this sample changes when you do
 * that — the interesting configuration is {@code shiguchi.security}, not this class.
 *
 * <p>The secret is hardcoded and printed at startup. That is fine for a sample whose whole
 * purpose is to be called from a shell, and would be a serious problem anywhere else.
 */
@Configuration
public class LocalJwtConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LocalJwtConfiguration.class);

    /** Must be at least 256 bits for HS256. */
    static final String SECRET = "sample-secured-gateway-development-secret-key-256";

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        var key = new SecretKeySpec(SECRET.getBytes(), "HmacSHA256");
        log.info("JWT: HS256 with a fixed development secret. A demo token is printed below.");
        log.info("demo token: {}", mintToken("demo-user", List.of("catalog.read")));
        return NimbusReactiveJwtDecoder.withSecretKey(key).build();
    }

    /** Mints a token the sample's README (and its test) can use. */
    static String mintToken(String subject, List<String> scopes) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer("sample-secured-gateway")
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
