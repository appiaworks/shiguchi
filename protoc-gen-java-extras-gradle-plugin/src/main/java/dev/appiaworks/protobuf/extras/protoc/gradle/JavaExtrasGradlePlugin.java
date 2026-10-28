package dev.appiaworks.protobuf.extras.protoc.gradle;

import com.google.protobuf.gradle.ProtobufExtension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * {@code io.github.appiaworks.protoc-java-extras}: Java-typed accessors on protoc-generated classes,
 * with nothing to wire by hand.
 *
 * <p>Applying it:
 *
 * <ul>
 *   <li>applies {@code com.google.protobuf} if the build has not;
 *   <li>resolves {@code io.github.appiaworks:protoc-gen-java-extras} into the
 *       {@code protocGenJavaExtras} configuration, where its version can be overridden;
 *   <li>writes a launcher for it and registers it with protoc as the {@code javaextras} plugin;
 *   <li>adds it to every {@code generateProto} task, writing into the Java generator's own output
 *       directory, which is what makes protoc's insertion points resolve;
 *   <li>adds {@code io.github.appiaworks:protobuf-java-extras}, which the generated accessors call, to
 *       {@code api} (or {@code implementation} when the project is not a library).
 * </ul>
 */
public class JavaExtrasGradlePlugin implements Plugin<Project> {

    static final String PROTOC_PLUGIN_NAME = "javaextras";
    static final String TOOL_CONFIGURATION = "protocGenJavaExtras";

    @Override
    public void apply(Project project) {
        JavaExtrasExtension extension = project.getExtensions().create("javaExtras", JavaExtrasExtension.class);
        project.getPluginManager().apply("com.google.protobuf");
        String version = ownVersion();

        Configuration tool = project.getConfigurations().create(TOOL_CONFIGURATION, c -> {
            c.setCanBeConsumed(false);
            c.setDescription("The protoc-gen-javaextras protoc plugin and its dependencies.");
            c.defaultDependencies(deps -> deps.add(
                    project.getDependencies().create("io.github.appiaworks:protoc-gen-java-extras:" + version)));
        });

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Provider<RegularFile> launcherFile = project.getLayout()
                .getBuildDirectory()
                .file("protoc-gen-javaextras/protoc-gen-javaextras" + (windows ? ".bat" : ""));
        TaskProvider<WriteProtocLauncher> launcher = project.getTasks()
                .register("writeJavaExtrasLauncher", WriteProtocLauncher.class, task -> {
                    task.setDescription("Writes the launcher protoc starts protoc-gen-javaextras with.");
                    task.getPluginClasspath().from(tool);
                    task.getJavaExecutable()
                            .set(new File(System.getProperty("java.home"), windows ? "bin/java.exe" : "bin/java")
                                    .getAbsolutePath());
                    task.getLauncher().set(launcherFile);
                });

        ProtobufExtension protobuf = project.getExtensions().getByType(ProtobufExtension.class);
        protobuf.plugins(locators -> locators.create(
                PROTOC_PLUGIN_NAME,
                locator -> locator.setPath(launcherFile.get().getAsFile().getAbsolutePath())));
        // The protobuf plugin runs this once the build script has been evaluated, so the options
        // read here are the ones the javaExtras block ended up with.
        protobuf.generateProtoTasks(tasks -> tasks.all().configureEach(task -> {
            task.dependsOn(launcher);
            task.getInputs().files(tool).withPropertyName("protocGenJavaExtrasClasspath");
            task.plugins(plugins -> plugins.create(PROTOC_PLUGIN_NAME, options -> {
                // Insertion points resolve only against files written to the same directory in the
                // same protoc run: share the Java generator's output directory.
                options.setOutputSubDir("java");
                for (String option : extension.toOptions()) options.option(option);
            }));
        }));

        project.afterEvaluate(p -> {
            if (!extension.getAddRuntimeDependency().get()) return;
            String scope = p.getConfigurations().findByName("api") != null ? "api" : "implementation";
            if (p.getConfigurations().findByName(scope) == null) return;
            p.getDependencies().add(scope, "io.github.appiaworks:protobuf-java-extras:" + version);
        });
    }

    /** The version this plugin was built as: the protoc plugin and the runtime library share it. */
    static String ownVersion() {
        try (InputStream in = JavaExtrasGradlePlugin.class.getResourceAsStream("plugin.properties")) {
            if (in == null) throw new IllegalStateException("plugin.properties is missing from the plugin jar");
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
