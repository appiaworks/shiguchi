package dev.appiaworks.shiguchi.bff.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

class StubIssuerTest {

    private static final String REDIRECT = "http://localhost:9999/cb";
    private static final String VERIFIER = "a-verifier-of-sufficient-length-0123456789-abcdefghij";

    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private StubIssuer issuer;

    @BeforeEach
    void start() {
        issuer = StubIssuer.start();
    }

    @AfterEach
    void stop() {
        issuer.close();
    }

    @Test
    @DisplayName("GIVEN a started issuer WHEN discovery is fetched SHOULD advertise endpoints under the issuer")
    void given_started_issuer_when_discovery_fetched_should_advertise_endpoints() throws Exception {
        HttpResponse<String> res = get("/.well-known/openid-configuration");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("\"authorization_endpoint\":\"" + issuer.issuer() + "/authorize\"")
                .contains("\"jwks_uri\":\"" + issuer.issuer() + "/jwks\"")
                .contains("end_session_endpoint")
                .contains("\"revocation_endpoint\":\"" + issuer.issuer() + "/revoke\"");
        assertThat(issuer.discoveryCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN discovery disabled WHEN fetched SHOULD answer 404")
    void given_discovery_disabled_when_fetched_should_answer_404() throws Exception {
        issuer.discovery(false);

        assertThat(get("/.well-known/openid-configuration").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN a valid authorize request WHEN sent SHOULD redirect with a code and the state")
    void given_valid_authorize_when_sent_should_redirect_with_code_and_state() throws Exception {
        HttpResponse<String> res = authorize("st-1", "n-1");

        assertThat(res.statusCode()).isEqualTo(302);
        String location = res.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(REDIRECT + "?code=").endsWith("&state=st-1");
        assertThat(issuer.lastAuthorize()).containsEntry("nonce", "n-1").containsEntry("state", "st-1");
    }

    @Test
    @DisplayName("GIVEN an authorize request without PKCE WHEN sent SHOULD answer 400")
    void given_authorize_without_pkce_when_sent_should_answer_400() throws Exception {
        HttpResponse<String> res =
                get("/authorize?response_type=code&client_id=gateway&redirect_uri=" + enc(REDIRECT) + "&state=s");

        assertThat(res.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN ES256 signing WHEN a code is exchanged SHOULD sign with ES256, serve the EC key and verify")
    void given_es256_signing_when_code_exchanged_should_issue_es256_tokens() throws Exception {
        issuer.signingAlgorithm("ES256");
        String code = codeFrom(authorize("s", "the-nonce"));

        Map<String, Object> body = com.nimbusds.jose.util.JSONObjectUtils.parse(
                token(code, VERIFIER, "gateway", "secret").body());

        assertThat(SignedJWT.parse((String) body.get("access_token"))
                        .getHeader()
                        .getAlgorithm())
                .isEqualTo(com.nimbusds.jose.JWSAlgorithm.ES256);
        assertThat(SignedJWT.parse((String) body.get("id_token")).getHeader().getAlgorithm())
                .isEqualTo(com.nimbusds.jose.JWSAlgorithm.ES256);
        assertThat(get("/jwks").body()).contains("\"kty\":\"EC\"").contains("P-256");
        Jwt access = NimbusReactiveJwtDecoder.withJwkSetUri(issuer.issuer() + "/jwks")
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)
                .build()
                .decode((String) body.get("access_token"))
                .block();
        assertThat(access.getSubject()).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN ES256 signing WHEN reset SHOULD go back to RS256")
    void given_es256_signing_when_reset_should_go_back_to_rs256() throws Exception {
        issuer.signingAlgorithm("ES256");

        issuer.reset();

        assertThat(SignedJWT.parse(issuer.accessToken("alice")).getHeader().getAlgorithm())
                .isEqualTo(com.nimbusds.jose.JWSAlgorithm.RS256);
        assertThat(get("/jwks").body()).contains("\"kty\":\"RSA\"");
    }

    @Test
    @DisplayName("GIVEN a code WHEN exchanged with the right verifier SHOULD issue tokens that verify against the JWKS")
    void given_code_when_exchanged_with_right_verifier_should_issue_verifiable_tokens() throws Exception {
        String code = codeFrom(authorize("s", "the-nonce"));

        HttpResponse<String> res = token(code, VERIFIER, "gateway", "secret");

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = com.nimbusds.jose.util.JSONObjectUtils.parse(res.body());
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(issuer.issuer() + "/jwks")
                .build();
        Jwt access = decoder.decode((String) body.get("access_token")).block();
        Jwt id = decoder.decode((String) body.get("id_token")).block();
        assertThat(access.getSubject()).isEqualTo("alice");
        assertThat(access.getAudience()).containsExactly("gateway-api");
        assertThat(id.getClaimAsString("nonce")).isEqualTo("the-nonce");
        assertThat(id.getAudience()).containsExactly("gateway");
        assertThat(SignedJWT.parse((String) body.get("id_token")).getHeader().getKeyID())
                .isNotBlank();
        assertThat(issuer.tokenCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a code WHEN exchanged with a wrong verifier SHOULD answer invalid_grant")
    void given_code_when_wrong_verifier_should_answer_invalid_grant() throws Exception {
        String code = codeFrom(authorize("s", "n"));

        HttpResponse<String> res = token(code, VERIFIER + "x", "gateway", "secret");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid_grant");
    }

    @Test
    @DisplayName("GIVEN a redeemed code WHEN exchanged again SHOULD answer invalid_grant")
    void given_redeemed_code_when_exchanged_again_should_answer_invalid_grant() throws Exception {
        String code = codeFrom(authorize("s", "n"));
        assertThat(token(code, VERIFIER, "gateway", "secret").statusCode()).isEqualTo(200);

        HttpResponse<String> res = token(code, VERIFIER, "gateway", "secret");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid_grant");
    }

    @Test
    @DisplayName("GIVEN basic auth with a wrong secret WHEN exchanging SHOULD answer 401 invalid_client")
    void given_wrong_secret_when_exchanging_should_answer_401() throws Exception {
        String code = codeFrom(authorize("s", "n"));

        HttpResponse<String> res = token(code, VERIFIER, "gateway", "nope");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("invalid_client");
    }

    @Test
    @DisplayName("GIVEN a refresh token WHEN refreshed SHOULD rotate it, return a new ID token and count the call")
    void given_refresh_token_when_refreshed_should_rotate_and_count() throws Exception {
        String rt = firstRefreshToken();

        HttpResponse<String> res = refresh(rt);

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = com.nimbusds.jose.util.JSONObjectUtils.parse(res.body());
        assertThat((String) body.get("refresh_token")).isNotBlank().isNotEqualTo(rt);
        assertThat((String) body.get("id_token")).isNotBlank();
        assertThat(SignedJWT.parse((String) body.get("access_token"))
                        .getJWTClaimsSet()
                        .getSubject())
                .isEqualTo("alice");
        assertThat(issuer.refreshCalls()).isEqualTo(1);
        assertThat(issuer.familyRevoked()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a used refresh token WHEN presented again SHOULD revoke the family, the new token included")
    void given_used_refresh_token_when_presented_again_should_revoke_family() throws Exception {
        String rt = firstRefreshToken();
        Map<String, Object> first =
                com.nimbusds.jose.util.JSONObjectUtils.parse(refresh(rt).body());
        String second = (String) first.get("refresh_token");

        HttpResponse<String> reuse = refresh(rt);
        HttpResponse<String> later = refresh(second);

        assertThat(reuse.statusCode()).isEqualTo(400);
        assertThat(reuse.body()).contains("invalid_grant");
        assertThat(later.statusCode()).isEqualTo(400);
        assertThat(later.body()).contains("invalid_grant");
        assertThat(issuer.familyRevoked()).isTrue();
        assertThat(issuer.refreshCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN two families WHEN one is revoked by reuse SHOULD leave the other working")
    void given_two_families_when_one_revoked_should_leave_other_working() throws Exception {
        String a = issuer.issueRefreshToken("alice");
        String b = issuer.issueRefreshToken("bob");
        refresh(a);
        refresh(a);

        assertThat(refresh(b).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("GIVEN every token revoked WHEN refreshed SHOULD answer invalid_grant")
    void given_all_tokens_revoked_when_refreshed_should_answer_invalid_grant() throws Exception {
        String rt = issuer.issueRefreshToken("alice");
        issuer.revokeAllRefreshTokens();

        assertThat(refresh(rt).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN the 5xx mode WHEN refreshed SHOULD answer that status and keep the token usable afterwards")
    void given_5xx_mode_when_refreshed_should_answer_status() throws Exception {
        String rt = issuer.issueRefreshToken("alice");
        issuer.refreshStatus(503);

        assertThat(refresh(rt).statusCode()).isEqualTo(503);

        issuer.refreshStatus(0);
        assertThat(refresh(rt).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("GIVEN options WHEN refreshed SHOULD omit the ID token and change the subject")
    void given_options_when_refreshed_should_omit_id_token_and_change_subject() throws Exception {
        String rt = issuer.issueRefreshToken("alice");
        issuer.refreshIssuesIdToken(false).refreshSubject("mallory");

        Map<String, Object> body =
                com.nimbusds.jose.util.JSONObjectUtils.parse(refresh(rt).body());

        assertThat(body).doesNotContainKey("id_token");
        assertThat(SignedJWT.parse((String) body.get("access_token"))
                        .getJWTClaimsSet()
                        .getSubject())
                .isEqualTo("mallory");
    }

    @Test
    @DisplayName("GIVEN a delay WHEN refreshed SHOULD hold the answer that long")
    void given_delay_when_refreshed_should_hold_the_answer() throws Exception {
        String rt = issuer.issueRefreshToken("alice");
        issuer.refreshDelay(java.time.Duration.ofMillis(400));
        long start = System.nanoTime();

        refresh(rt);

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(java.time.Duration.ofMillis(350));
    }

    @Test
    @DisplayName("GIVEN a refresh grant WHEN the client secret is wrong SHOULD answer 401 invalid_client")
    void given_refresh_with_wrong_secret_should_answer_401() throws Exception {
        String rt = issuer.issueRefreshToken("alice");

        assertThat(refresh(rt, "gateway", "nope").statusCode()).isEqualTo(401);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private HttpResponse<String> authorize(String state, String nonce) throws Exception {
        return get("/authorize?response_type=code&client_id=gateway&redirect_uri=" + enc(REDIRECT) + "&state=" + state
                + "&nonce=" + nonce + "&code_challenge=" + challenge(VERIFIER) + "&code_challenge_method=S256");
    }

    private HttpResponse<String> token(String code, String verifier, String id, String secret) throws Exception {
        String form = "grant_type=authorization_code&code=" + code + "&redirect_uri=" + enc(REDIRECT)
                + "&code_verifier=" + verifier;
        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(URI.create(issuer.issuer() + "/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", "Basic " + basic)
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String firstRefreshToken() throws Exception {
        String code = codeFrom(authorize("s", "n"));
        Map<String, Object> body = com.nimbusds.jose.util.JSONObjectUtils.parse(
                token(code, VERIFIER, "gateway", "secret").body());
        return (String) body.get("refresh_token");
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return refresh(refreshToken, "gateway", "secret");
    }

    private HttpResponse<String> refresh(String refreshToken, String id, String secret) throws Exception {
        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(URI.create(issuer.issuer() + "/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", "Basic " + basic)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=refresh_token&refresh_token=" + enc(refreshToken)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(issuer.issuer() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String codeFrom(HttpResponse<String> redirect) {
        String location = redirect.headers().firstValue("Location").orElseThrow();
        String afterCode = location.substring(location.indexOf("code=") + 5);
        return afterCode.substring(0, afterCode.indexOf('&'));
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
