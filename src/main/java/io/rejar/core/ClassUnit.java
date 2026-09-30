package io.rejar.core;

import java.util.List;

/**
 * A top-level class together with its nested classes: the unit that is decompiled and recompiled as one source file.
 *
 * @param id        prefix + internal top-level name, e.g. {@code BOOT-INF/classes/com/acme/Foo}
 * @param prefix    class root inside the jar, e.g. {@code BOOT-INF/classes/} (empty for plain jars)
 * @param className internal top-level class name, e.g. {@code com/acme/Foo}
 * @param entries   jar entries belonging to this unit (outer class first)
 */
public record ClassUnit(String id, String prefix, String className, List<String> entries) {

    public String outerEntry() {
        return id + ClassNames.CLASS_SUFFIX;
    }

    public String fqcn() {
        return ClassNames.toFqcn(className);
    }

    public String simpleName() {
        return ClassNames.simpleName(className);
    }

    public String packageName() {
        int slash = className.lastIndexOf('/');
        return slash < 0 ? "" : ClassNames.toFqcn(className.substring(0, slash));
    }

    /** Path of the source file relative to a source root, e.g. {@code com/acme/Foo.java}. */
    public String sourcePath() {
        return className + ".java";
    }
}
