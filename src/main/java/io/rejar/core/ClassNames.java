package io.rejar.core;

import java.util.Set;
import javax.lang.model.SourceVersion;

/** Helpers mapping jar entry paths to class names and class file versions. */
public final class ClassNames {

    public static final String CLASS_SUFFIX = ".class";

    private static final String[] CLASS_ROOTS = {"BOOT-INF/classes/", "WEB-INF/classes/"};
    private static final String VERSIONS_ROOT = "META-INF/versions/";

    private ClassNames() {
    }

    public static boolean isClass(String path) {
        return path.endsWith(CLASS_SUFFIX) && !path.startsWith(HistoryStore.ROOT);
    }

    /** Directory prefix in which the class file lives relative to its package root (e.g. {@code BOOT-INF/classes/}). */
    public static String prefixOf(String path) {
        for (String root : CLASS_ROOTS) {
            if (path.startsWith(root)) {
                return root;
            }
        }
        if (path.startsWith(VERSIONS_ROOT)) {
            int slash = path.indexOf('/', VERSIONS_ROOT.length());
            if (slash > 0) {
                return path.substring(0, slash + 1);
            }
        }
        return "";
    }

    /** Internal (slash separated) class name of a class entry, e.g. {@code com/acme/Foo$Bar}. */
    public static String internalName(String path) {
        return path.substring(prefixOf(path).length(), path.length() - CLASS_SUFFIX.length());
    }

    /**
     * Identifier of the top-level class owning an entry: prefix + internal top-level name.
     * Nested classes ({@code Foo$Bar}) are attached to their outer class when that class exists.
     */
    public static String unitIdOf(String path, Set<String> existingEntries) {
        String prefix = prefixOf(path);
        String name = internalName(path);
        int simpleStart = name.lastIndexOf('/') + 1;
        int dollar = name.indexOf('$', simpleStart + 1);
        while (dollar > 0) {
            String candidate = name.substring(0, dollar);
            if (existingEntries.contains(prefix + candidate + CLASS_SUFFIX)) {
                return prefix + candidate;
            }
            dollar = name.indexOf('$', dollar + 1);
        }
        return prefix + name;
    }

    /** Major class file version, or -1 if the bytes are not a class file. */
    public static int majorVersion(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 8
                || (classBytes[0] & 0xFF) != 0xCA || (classBytes[1] & 0xFF) != 0xFE
                || (classBytes[2] & 0xFF) != 0xBA || (classBytes[3] & 0xFF) != 0xBE) {
            return -1;
        }
        return ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
    }

    /** Java release matching a class file major version (45..51 are mapped to 8, the oldest javac 21 target). */
    public static int releaseOf(int major) {
        int release = major - 44;
        int latest = SourceVersion.latestSupported().ordinal();
        if (release < 8) {
            return 8;
        }
        return Math.min(release, latest);
    }

    public static String toFqcn(String internalName) {
        return internalName.replace('/', '.');
    }

    public static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
