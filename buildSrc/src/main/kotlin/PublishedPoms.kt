import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * What Maven Central would reject in a generated POM, found before a release instead of during one.
 *
 * Gradle resolves a version-less dependency through its own platform constraints, so the build and
 * every test pass while the POM says nothing about the version. Only Central's validation notices,
 * after the build, the image and the chart have already gone out (v0.15.0, fixed by #31).
 *
 * A version-less dependency is valid when the POM can still supply the version: a BOM import or a
 * matching managed entry. A BOM's contents are not fetched, so one that does not manage the
 * dependency passes here. Central resolves only as far as this check does.
 */
object PublishedPoms {

    private val REQUIRED_TEXT = listOf("groupId", "artifactId", "version", "name", "description", "url")

    /** Returns one line per problem in [pom]; empty when Central would accept it. */
    fun problems(pom: File): List<String> {
        val project = parse(pom)
        return missingMetadata(project) + unversionedDependencies(project)
    }

    private fun missingMetadata(project: Element): List<String> {
        val problems = REQUIRED_TEXT.filter { project.text(it) == null }.map { "Missing <$it>" }.toMutableList()
        if (project.child("licenses")?.children("license").isNullOrEmpty()) problems += "Missing <licenses>"
        if (project.child("developers")?.children("developer").isNullOrEmpty()) problems += "Missing <developers>"
        if (project.child("scm")?.text("url") == null) problems += "Missing <scm><url>"
        return problems
    }

    private fun unversionedDependencies(project: Element): List<String> {
        val managed = project.child("dependencyManagement")?.dependencies().orEmpty()
        if (managed.any { it.text("scope") == "import" && it.text("type") == "pom" }) return emptyList()

        val managedVersions = managed.filter { it.text("version") != null }.map { it.coordinates() }.toSet()
        return project.dependencies()
            .filter { it.text("version") == null && it.coordinates() !in managedVersions }
            .map {
                "Dependency version information is missing for dependency: ${it.coordinates()}. " +
                    "Declare a version, or import the BOM that manages it with api(platform(...))."
            }
    }

    private fun parse(pom: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(pom).documentElement
    }

    private fun Element.dependencies() = child("dependencies")?.children("dependency").orEmpty()

    private fun Element.coordinates() = "${text("groupId")}:${text("artifactId")}"

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.localName == name }
    }

    private fun Element.child(name: String) = children(name).firstOrNull()

    private fun Element.text(name: String) = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
