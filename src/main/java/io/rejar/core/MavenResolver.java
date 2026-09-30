package io.rejar.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Resolves the compile classpath of a pom by running {@code mvn dependency:build-classpath}. */
public final class MavenResolver {

    private static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private MavenResolver() {
    }

    /** Locates the Maven executable: configured path, MAVEN_HOME / M2_HOME, then PATH. */
    public static Optional<Path> findMaven(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Files.isDirectory(p)) {
                return executableIn(p.resolve("bin")).or(() -> executableIn(p));
            }
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        for (String env : List.of("MAVEN_HOME", "M2_HOME")) {
            String home = System.getenv(env);
            if (home != null && !home.isBlank()) {
                Optional<Path> found = executableIn(Path.of(home, "bin"));
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    try {
                        Optional<Path> found = executableIn(Path.of(dir.trim()));
                        if (found.isPresent()) {
                            return found;
                        }
                    } catch (RuntimeException ignored) {
                        // invalid PATH element
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executableIn(Path dir) {
        List<String> names = WINDOWS ? List.of("mvn.cmd", "mvn.bat", "mvn.exe") : List.of("mvn");
        for (String name : names) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Runs Maven on the pom and returns its compile-scope dependencies (compile, provided and system scopes).
     *
     * @param mvn       maven executable
     * @param pom       pom.xml to resolve
     * @param extraArgs additional arguments, e.g. {@code -o} or {@code -s settings.xml}
     * @param log       receives Maven output line by line
     * @param cancel    set to true to abort
     */
    public static List<Path> resolve(Path mvn, Path pom, String extraArgs, Consumer<String> log, AtomicBoolean cancel)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("rejar-cp", ".txt");
        try {
            List<String> command = new ArrayList<>();
            command.add(mvn.toString());
            command.add("-B");
            command.add("-f");
            command.add(pom.toAbsolutePath().toString());
            if (extraArgs != null && !extraArgs.isBlank()) {
                command.addAll(Arrays.asList(extraArgs.trim().split("\\s+")));
            }
            command.add(DEPENDENCY_PLUGIN + ":build-classpath");
            command.add("-Dmdep.outputFile=" + output.toAbsolutePath());
            command.add("-DincludeScope=compile");
            log.accept("> " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.directory(pom.toAbsolutePath().getParent().toFile());
            Process process = pb.start();
            Charset charset = WINDOWS ? Charset.defaultCharset() : StandardCharsets.UTF_8;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.accept(line);
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            }, "maven-output");
            reader.setDaemon(true);
            reader.start();
            while (!process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.get()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    throw new InterruptedException("Maven resolution cancelled");
                }
            }
            reader.join(2000);
            if (process.exitValue() != 0) {
                throw new IOException("Maven failed with exit code " + process.exitValue() + " (see log)");
            }
            String cp = Files.readString(output, StandardCharsets.UTF_8).trim();
            List<Path> result = new ArrayList<>();
            if (!cp.isEmpty()) {
                for (String element : cp.split(File.pathSeparator)) {
                    if (!element.isBlank()) {
                        result.add(Path.of(element.trim()));
                    }
                }
            }
            log.accept("Resolved " + result.size() + " dependencies.");
            return result;
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
