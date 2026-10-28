package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("BffReturnTo: which application a return_to belongs to, and which values are refused")
class BffReturnToTest {

    private final BffReturnTo returnTo = new BffReturnTo(new BffApplications(BffTestFixtures.properties()));

    private Optional<String> applicationOf(String value) {
        return returnTo.resolve(value).map(m -> m.application().id());
    }

    @Test
    @DisplayName(
            "GIVEN a URL under an application's return-to WHEN resolved SHOULD pick that application and keep the URL")
    void given_urlUnderReturnTo_when_resolved_should_pickApplication() {
        Optional<BffReturnTo.Match> match = returnTo.resolve("https://eve.example.com/app/deep/page?x=1");

        assertThat(match).isPresent();
        assertThat(match.get().application().id()).isEqualTo("eve");
        assertThat(match.get().returnTo()).isEqualTo("https://eve.example.com/app/deep/page?x=1");
    }

    @Test
    @DisplayName("GIVEN the return-to entry itself WHEN resolved SHOULD match")
    void given_exactEntry_when_resolved_should_match() {
        assertThat(applicationOf("https://eve.example.com/app")).contains("eve");
    }

    @Test
    @DisplayName("GIVEN two applications on different origins WHEN resolved SHOULD pick each by origin")
    void given_twoApplications_when_resolved_should_pickByOrigin() {
        assertThat(applicationOf("https://fin.example.com/anything")).contains("fin");
        assertThat(applicationOf("https://eve.example.com/app/x")).contains("eve");
    }

    @Test
    @DisplayName("GIVEN an entry with a trailing slash WHEN resolved with only the origin SHOULD match as /")
    void given_originOnlyUrl_when_resolved_should_matchRootEntry() {
        assertThat(applicationOf("https://fin.example.com")).contains("fin");
    }

    @Test
    @DisplayName("GIVEN the default port written out WHEN resolved SHOULD still match")
    void given_defaultPortWrittenOut_when_resolved_should_match() {
        assertThat(applicationOf("https://EVE.example.com:443/app")).contains("eve");
    }

    @Test
    @DisplayName("GIVEN a sibling path that only shares a prefix WHEN resolved SHOULD refuse it")
    void given_siblingPathSharingPrefix_when_resolved_should_refuse() {
        assertThat(applicationOf("https://eve.example.com/app-evil")).isEmpty();
        assertThat(applicationOf("https://eve.example.com/application")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a path outside the entry on the right origin WHEN resolved SHOULD refuse it")
    void given_pathOutsideEntry_when_resolved_should_refuse() {
        assertThat(applicationOf("https://eve.example.com/other")).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                "https://app.example.com.evil.com/",
                "https://eve.example.com.evil.com/app",
                "https://eve.example.com@evil.com/app",
                "https://eve.example.com:pw@eve.example.com/app",
                "https:\\\\evil.com",
                "https://eve.example.com\\@evil.com/app",
                "/app",
                "app",
                "//evil.com/app",
                "javascript:alert(1)",
                "ftp://eve.example.com/app",
                "https://eve.example.com/ app",
                "https://eve.example.com/app\n",
                "https://eve.example.com/app\t",
                "https:///app",
                "http://eve.example.com/app",
            })
    @DisplayName("GIVEN a hostile or malformed return_to WHEN resolved SHOULD refuse it")
    void given_hostileReturnTo_when_resolved_should_refuse(String value) {
        assertThat(returnTo.resolve(value)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no return_to and several applications WHEN resolved SHOULD refuse")
    void given_noReturnToAndSeveralApplications_when_resolved_should_refuse() {
        assertThat(returnTo.resolve(null)).isEmpty();
        assertThat(returnTo.resolve("")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no return_to and a single application WHEN resolved SHOULD use its first return-to")
    void given_noReturnToAndSingleApplication_when_resolved_should_useFirstEntry() {
        BffProperties properties = BffTestFixtures.properties();
        properties.getApplications().remove("fin");
        BffReturnTo single = new BffReturnTo(new BffApplications(properties));

        Optional<BffReturnTo.Match> match = single.resolve(null);

        assertThat(match).isPresent();
        assertThat(match.get().application().id()).isEqualTo("eve");
        assertThat(match.get().returnTo()).isEqualTo("https://eve.example.com/app");
    }

    @Test
    @DisplayName("GIVEN two applications whose return-to entries both cover a URL WHEN resolved SHOULD refuse it")
    void given_urlCoveredByTwoApplications_when_resolved_should_refuse() {
        BffProperties properties = BffTestFixtures.properties();
        properties.getApplications().get("fin").setReturnTo(java.util.List.of("https://eve.example.com/app"));
        BffReturnTo ambiguous = new BffReturnTo(new BffApplications(properties));

        assertThat(ambiguous.resolve("https://eve.example.com/app/x")).isEmpty();
    }
}
