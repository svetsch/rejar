package io.rejar.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compilation settings of one opened jar. The classpath is, in order: pending changes, the jar content, jars nested
 * in the jar, dependencies resolved with Maven and custom entries.
 */
public final class ClasspathConfig {

    /** Include jars nested in the jar (BOOT-INF/lib, WEB-INF/lib...). */
    public boolean includeNestedJars = true;

    /** Resolve the dependencies declared in a pom with Maven. */
    public boolean useMaven = true;

    /** Pom embedded in the jar ({@code META-INF/maven/.../pom.xml}), used when {@link #externalPom} is null. */
    public String embeddedPom;

    /** Pom file on disk to resolve dependencies from, instead of the embedded one. */
    public Path externalPom;

    /** Dependencies resolved by the last Maven run (null = not resolved yet). */
    public List<Path> mavenClasspath;

    /** Custom jars / class folders. */
    public List<Path> customEntries = new ArrayList<>();

    /** Forced javac --release, 0 = derived from the class file version. */
    public int release;

    /** Additional javac options, whitespace separated. */
    public String javacOptions = "";

    public ClasspathConfig copy() {
        ClasspathConfig c = new ClasspathConfig();
        c.includeNestedJars = includeNestedJars;
        c.useMaven = useMaven;
        c.embeddedPom = embeddedPom;
        c.externalPom = externalPom;
        c.mavenClasspath = mavenClasspath == null ? null : List.copyOf(mavenClasspath);
        c.customEntries = new ArrayList<>(customEntries);
        c.release = release;
        c.javacOptions = javacOptions;
        return c;
    }

    public boolean hasPomSource() {
        return externalPom != null || embeddedPom != null;
    }

    public String pomLabel() {
        return externalPom != null ? externalPom.toString() : embeddedPom;
    }
}
