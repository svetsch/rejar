package io.rejar.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Assembles the compilation classpath of a jar from its {@link ClasspathConfig}. */
public final class ClasspathBuilder {

    private static final List<String> CLASS_ROOTS = List.of("BOOT-INF/classes/", "WEB-INF/classes/");

    private final JarModel model;
    private final Map<String, Path> extractedRoots = new LinkedHashMap<>();
    private List<Path> extractedNested;
    private final AtomicInteger pendingCounter = new AtomicInteger();

    public ClasspathBuilder(JarModel model) {
        this.model = model;
    }

    /**
     * Builds the full compilation classpath: pending changes first (so already edited classes are visible), then
     * the jar, nested jars, Maven dependencies and custom entries.
     */
    public List<Path> build(ClasspathConfig config) throws IOException {
        Set<Path> cp = new LinkedHashSet<>();
        cp.add(writePendingClasses());
        cp.addAll(jarContent());
        if (config.includeNestedJars) {
            cp.addAll(nestedJars());
        }
        if (config.useMaven && config.mavenClasspath != null) {
            cp.addAll(config.mavenClasspath);
        }
        cp.addAll(config.customEntries);
        return new ArrayList<>(cp);
    }

    /** Classpath entries outside the jar itself, used by the decompiler to resolve referenced types. */
    public List<Path> libraries(ClasspathConfig config) throws IOException {
        List<Path> libs = new ArrayList<>();
        if (config.includeNestedJars) {
            libs.addAll(nestedJars());
        }
        if (config.useMaven && config.mavenClasspath != null) {
            libs.addAll(config.mavenClasspath);
        }
        libs.addAll(config.customEntries);
        return libs;
    }

    /** The jar itself, or its extracted class roots for Spring Boot / war layouts. */
    public synchronized List<Path> jarContent() throws IOException {
        List<Path> result = new ArrayList<>();
        boolean plainClasses = false;
        Set<String> roots = new LinkedHashSet<>();
        for (ZipEntry e : model.originalEntries()) {
            String name = e.getName();
            if (ClassNames.isClass(name)) {
                String prefix = ClassNames.prefixOf(name);
                if (prefix.isEmpty()) {
                    plainClasses = true;
                } else if (CLASS_ROOTS.contains(prefix)) {
                    roots.add(prefix);
                }
            }
        }
        for (String root : roots) {
            Path dir = extractedRoots.get(root);
            if (dir == null) {
                dir = model.workDir().resolve("extracted").resolve(root.replace('/', '_'));
                extract(root, dir);
                extractedRoots.put(root, dir);
            }
            result.add(dir);
        }
        if (plainClasses || roots.isEmpty()) {
            result.add(model.file());
        }
        return result;
    }

    private void extract(String root, Path target) throws IOException {
        Files.createDirectories(target);
        for (ZipEntry e : model.originalEntries()) {
            String name = e.getName();
            if (name.startsWith(root) && ClassNames.isClass(name)) {
                Path file = safeResolve(target, name.substring(root.length()));
                Files.createDirectories(file.getParent());
                try (InputStream in = model.zip().getInputStream(e)) {
                    Files.copy(in, file);
                }
            }
        }
    }

    /** Jars nested in the jar, extracted to the work directory. */
    public synchronized List<Path> nestedJars() throws IOException {
        if (extractedNested == null) {
            List<Path> jars = new ArrayList<>();
            Path dir = model.workDir().resolve("nested");
            for (ZipEntry e : model.originalEntries()) {
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".jar")) {
                    Path file = safeResolve(dir, e.getName());
                    Files.createDirectories(file.getParent());
                    try (InputStream in = model.zip().getInputStream(e)) {
                        Files.copy(in, file);
                    }
                    jars.add(file);
                }
            }
            extractedNested = jars;
        }
        return extractedNested;
    }

    /** Writes the pending class files (without their class-root prefix) to a fresh directory. */
    public Path writePendingClasses() throws IOException {
        Path dir = model.workDir().resolve("pending-" + pendingCounter.incrementAndGet());
        Files.createDirectories(dir);
        for (PendingChange change : model.pendingList()) {
            if (!change.isDelete() && ClassNames.isClass(change.path())) {
                Path file = safeResolve(dir, ClassNames.internalName(change.path()) + ClassNames.CLASS_SUFFIX);
                Files.createDirectories(file.getParent());
                Files.write(file, change.newBytes());
            }
        }
        return dir;
    }

    /** Writes the pom to resolve (embedded one extracted to the work directory). */
    public Path pomFile(ClasspathConfig config) throws IOException {
        if (config.externalPom != null) {
            return config.externalPom;
        }
        if (config.embeddedPom == null) {
            throw new IOException("No pom.xml selected");
        }
        byte[] pom = model.readOriginal(config.embeddedPom);
        if (pom == null) {
            throw new IOException("Pom not found in jar: " + config.embeddedPom);
        }
        Path dir = model.workDir().resolve("maven").resolve(config.embeddedPom.replace('/', '_'));
        Files.createDirectories(dir);
        Path file = dir.resolve("pom.xml");
        Files.write(file, pom);
        return file;
    }

    private static Path safeResolve(Path dir, String relative) throws IOException {
        Path resolved = dir.resolve(relative).normalize();
        if (!resolved.startsWith(dir)) {
            throw new IOException("Illegal entry path: " + relative);
        }
        return resolved;
    }

    /** Looks up class bytes in a list of jars / directories; used by the decompiler for referenced types. */
    public static final class Lookup implements DecompilerService.ClassLookup, Closeable {
        private final List<Path> entries;
        private final Map<Path, ZipFile> opened = new LinkedHashMap<>();

        public Lookup(List<Path> entries) {
            this.entries = List.copyOf(entries);
        }

        @Override
        public synchronized byte[] find(String internalName) throws IOException {
            String resource = internalName + ClassNames.CLASS_SUFFIX;
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    Path file = entry.resolve(resource);
                    if (Files.isRegularFile(file)) {
                        return Files.readAllBytes(file);
                    }
                } else if (Files.isRegularFile(entry)) {
                    ZipFile zip = opened.get(entry);
                    if (zip == null) {
                        try {
                            zip = new ZipFile(entry.toFile());
                        } catch (IOException e) {
                            continue;
                        }
                        opened.put(entry, zip);
                    }
                    ZipEntry ze = zip.getEntry(resource);
                    if (ze != null) {
                        try (InputStream in = zip.getInputStream(ze)) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public synchronized void close() {
            for (ZipFile zip : opened.values()) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            opened.clear();
        }
    }
}
