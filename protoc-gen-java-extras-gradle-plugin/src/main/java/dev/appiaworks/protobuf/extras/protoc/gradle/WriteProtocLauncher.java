package dev.appiaworks.protobuf.extras.protoc.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Writes the program protoc starts for {@code protoc-gen-javaextras}: a shell script, or a batch
 * file on Windows, that runs the plugin on a given JVM with a given classpath.
 *
 * <p>protoc runs a plugin as an executable and the plugin is a jar, so something has to stand in
 * between. The script names the JVM by absolute path, so it does not depend on {@code java}
 * being on the PATH of whatever environment the build runs in.
 */
@DisableCachingByDefault(
        because =
                "A two-line script holding absolute paths: quicker to write than to fetch, and wrong on another machine")
public abstract class WriteProtocLauncher extends DefaultTask {

    static final String MAIN_CLASS = "dev.appiaworks.protobuf.extras.protoc.JavaExtrasPlugin";

    /** The protoc plugin and its dependencies. */
    @Classpath
    public abstract ConfigurableFileCollection getPluginClasspath();

    /** Absolute path of the {@code java} executable the plugin runs on. */
    @Input
    public abstract Property<String> getJavaExecutable();

    @OutputFile
    public abstract RegularFileProperty getLauncher();

    @TaskAction
    void write() throws IOException {
        File launcher = getLauncher().get().getAsFile();
        String classpath = getPluginClasspath().getFiles().stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(File.pathSeparator));
        String java = getJavaExecutable().get();
        boolean batch = launcher.getName().endsWith(".bat");
        String script = batch
                ? "@echo off\r\n\"" + java + "\" -cp \"" + classpath + "\" " + MAIN_CLASS + "\r\n"
                : "#!/bin/sh\nexec \"" + java + "\" -cp \"" + classpath + "\" " + MAIN_CLASS + "\n";
        Files.createDirectories(launcher.getParentFile().toPath());
        Files.writeString(launcher.toPath(), script, StandardCharsets.UTF_8);
        if (!batch && !launcher.setExecutable(true)) {
            throw new IOException("Cannot make " + launcher + " executable");
        }
    }
}
