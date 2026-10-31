import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.xml.sax.SAXParseException

/**
 * The rule that decides whether Maven Central would accept a generated POM. The fixtures follow the
 * POMs Gradle generates; bom-import.xml is the published shape of the 0.15.0 UI starter, and
 * versionless-without-bom.xml is the shape Central rejected before #31.
 */
@DisplayName("PublishedPoms")
class PublishedPomsTest {

    private fun fixture(name: String) = File(javaClass.getResource("/poms/$name")!!.toURI())

    @Test
    @DisplayName("A version-less dependency covered by an imported BOM is accepted")
    fun GIVEN_versionlessDependency_WHEN_bomIsImported_SHOULD_reportNoProblems() {
        assertThat(PublishedPoms.problems(fixture("bom-import.xml"))).isEmpty()
    }

    @Test
    @DisplayName("A version-less dependency with no BOM is reported in Central's wording")
    fun GIVEN_versionlessDependency_WHEN_nothingManagesIt_SHOULD_reportTheDependency() {
        assertThat(PublishedPoms.problems(fixture("versionless-without-bom.xml")))
            .singleElement()
            .asString()
            .contains("Dependency version information is missing for dependency")
            .contains("org.springframework.boot:spring-boot-starter-webflux")
    }

    @Test
    @DisplayName("A version-less dependency with a matching managed version is accepted")
    fun GIVEN_versionlessDependency_WHEN_managedDirectly_SHOULD_reportNoProblems() {
        assertThat(PublishedPoms.problems(fixture("managed-directly.xml"))).isEmpty()
    }

    @Test
    @DisplayName("A managed entry for a different artifact does not cover a dependency")
    fun GIVEN_versionlessDependency_WHEN_managedEntryIsForAnotherArtifact_SHOULD_reportTheDependency() {
        assertThat(PublishedPoms.problems(fixture("managed-other-artifact.xml")))
            .singleElement()
            .asString()
            .contains("com.example:unmanaged")
    }

    @Test
    @DisplayName("Every missing piece of required metadata is reported")
    fun GIVEN_pomWithoutMetadata_WHEN_checked_SHOULD_reportEachMissingElement() {
        assertThat(PublishedPoms.problems(fixture("missing-metadata.xml")))
            .containsExactlyInAnyOrder(
                "Missing <description>",
                "Missing <url>",
                "Missing <licenses>",
                "Missing <developers>",
                "Missing <scm><url>",
            )
    }

    @Test
    @DisplayName("A name inside a nested element does not count as the project's name")
    fun GIVEN_nameOnlyInsideLicense_WHEN_checked_SHOULD_reportMissingName() {
        assertThat(PublishedPoms.problems(fixture("nested-name-only.xml"))).containsExactly("Missing <name>")
    }

    @Test
    @DisplayName("A POM with a DOCTYPE is refused rather than parsed")
    fun GIVEN_pomWithDoctype_WHEN_checked_SHOULD_failToParse() {
        assertThatThrownBy { PublishedPoms.problems(fixture("doctype.xml")) }
            .isInstanceOf(SAXParseException::class.java)
    }
}
