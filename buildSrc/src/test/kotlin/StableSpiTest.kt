import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The rule that decides whether an SPI change breaks the applications implementing it. Each case
 * compiles two tiny jars — a "before" and an "after" — and asks what the gate would say, because
 * the thing being tested is a judgement about real class files.
 */
@DisplayName("StableSpi")
class StableSpiTest {

    @TempDir lateinit var tmp: File

    private val marker =
        """
        package dev.appiaworks.protobuf.extras.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
        public @interface ApiStatus {
            Status value();
            String since() default "";
            enum Status { STABLE, EXPERIMENTAL, DEPRECATED, INTERNAL }
        }
        """.trimIndent()

    /** Compiles the given sources into a jar and returns it. */
    private fun jar(name: String, vararg sources: Pair<String, String>): File {
        val srcDir = File(tmp, "$name-src").apply { mkdirs() }
        val outDir = File(tmp, "$name-classes").apply { mkdirs() }
        val files =
            sources.map { (path, code) ->
                File(srcDir, path).apply {
                    parentFile.mkdirs()
                    writeText(code)
                }
            }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "needs a JDK, not a JRE" }
        val ok =
            compiler.run(
                null,
                null,
                null,
                "-d",
                outDir.absolutePath,
                *files.map { it.absolutePath }.toTypedArray(),
            )
        require(ok == 0) { "test fixture did not compile" }

        val jarFile = File(tmp, "$name.jar")
        JarOutputStream(jarFile.outputStream()).use { out ->
            outDir.walkTopDown().filter { it.isFile }.forEach { classFile ->
                out.putNextEntry(JarEntry(classFile.relativeTo(outDir).invariantSeparatorsPath))
                out.write(classFile.readBytes())
                out.closeEntry()
            }
        }
        return jarFile
    }

    private fun renderer(body: String, status: String = "STABLE") =
        "dev/appiaworks/shiguchi/Renderer.java" to
            """
            package dev.appiaworks.shiguchi;
            import dev.appiaworks.protobuf.extras.api.ApiStatus;
            @ApiStatus(value = ApiStatus.Status.$status)
            public interface Renderer {
            $body
            }
            """
                .trimIndent()

    private val markerSource = "dev/appiaworks/protobuf/extras/api/ApiStatus.java" to marker

    @Test
    @DisplayName("GIVEN a stable interface WHEN it gains an abstract method SHOULD report it, since every implementor stops compiling")
    fun given_stableInterface_when_abstractMethodAdded_should_report() {
        val before = jar("before", markerSource, renderer("    String render();"))
        val after = jar("after", markerSource, renderer("    String render();\n    String contentType();"))

        assertThat(StableSpi.addedAbstractMethods(before, after, emptySet()))
            .containsExactly("dev.appiaworks.shiguchi.Renderer.contentType()")
    }

    @Test
    @DisplayName("GIVEN a stable interface WHEN it gains a default method SHOULD report nothing, because that is how an SPI grows")
    fun given_stableInterface_when_defaultMethodAdded_should_reportNothing() {
        val before = jar("before", markerSource, renderer("    String render();"))
        val after =
            jar(
                "after",
                markerSource,
                renderer("    String render();\n    default String contentType() { return \"application/json\"; }"),
            )

        assertThat(StableSpi.addedAbstractMethods(before, after, emptySet())).isEmpty()
    }

    @Test
    @DisplayName("GIVEN an experimental interface WHEN it gains an abstract method SHOULD report nothing, since it carries no promise")
    fun given_experimentalInterface_when_abstractMethodAdded_should_reportNothing() {
        val before = jar("before", markerSource, renderer("    String render();", status = "EXPERIMENTAL"))
        val after =
            jar("after", markerSource, renderer("    String render();\n    String contentType();", status = "EXPERIMENTAL"))

        assertThat(StableSpi.addedAbstractMethods(before, after, emptySet())).isEmpty()
    }

    @Test
    @DisplayName("GIVEN an unannotated interface WHEN it gains an abstract method SHOULD report nothing, because unmarked means internal")
    fun given_unannotatedInterface_when_abstractMethodAdded_should_reportNothing() {
        val plain = { body: String ->
            "dev/appiaworks/shiguchi/Plain.java" to
                "package dev.appiaworks.shiguchi;\npublic interface Plain {\n$body\n}\n"
        }
        val before = jar("before", markerSource, plain("    String render();"))
        val after = jar("after", markerSource, plain("    String render();\n    String extra();"))

        assertThat(StableSpi.addedAbstractMethods(before, after, emptySet())).isEmpty()
    }

    @Test
    @DisplayName("GIVEN a stable interface that is new in this release WHEN compared SHOULD report nothing, since nobody implements it yet")
    fun given_newInterface_when_compared_should_reportNothing() {
        val before = jar("before", markerSource)
        val after = jar("after", markerSource, renderer("    String render();\n    String contentType();"))

        assertThat(StableSpi.addedAbstractMethods(before, after, emptySet())).isEmpty()
    }

    @Test
    @DisplayName("GIVEN a stable interface WHEN a method is removed SHOULD report nothing here, because japicmp already fails that")
    fun given_removedMethod_when_compared_should_leaveItToJapicmp() {
        val before = jar("before", markerSource, renderer("    String render();\n    String contentType();"))
        val after = jar("after", markerSource, renderer("    String render();"))

        assertThat(StableSpi.addedAbstractMethods(before, after, emptySet())).isEmpty()
    }
}
