package io.rejar.ui;

import io.rejar.core.ClasspathConfig;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** User preferences persisted with {@link Preferences}. */
public final class Settings {

    private static final Preferences ROOT = Preferences.userRoot().node("io/rejar");
    private static final String SEP = "\n";
    private static final int MAX_RECENT = 12;

    private Settings() {
    }

    public static String mavenExecutable() {
        return ROOT.get("maven.executable", "");
    }

    public static void setMavenExecutable(String value) {
        ROOT.put("maven.executable", value == null ? "" : value.trim());
    }

    public static String mavenArgs() {
        return ROOT.get("maven.args", "");
    }

    public static void setMavenArgs(String value) {
        ROOT.put("maven.args", value == null ? "" : value.trim());
    }

    public static int fontSize() {
        return ROOT.getInt("editor.fontSize", 13);
    }

    public static void setFontSize(int size) {
        ROOT.putInt("editor.fontSize", size);
    }

    public static boolean darkTheme() {
        return ROOT.getBoolean("ui.dark", false);
    }

    public static void setDarkTheme(boolean dark) {
        ROOT.putBoolean("ui.dark", dark);
    }

    public static File lastDirectory() {
        String dir = ROOT.get("lastDirectory", null);
        if (dir != null) {
            File f = new File(dir);
            if (f.isDirectory()) {
                return f;
            }
        }
        return null;
    }

    public static void setLastDirectory(File dir) {
        if (dir != null) {
            ROOT.put("lastDirectory", dir.isDirectory() ? dir.getAbsolutePath() : dir.getParent());
        }
    }

    public static List<Path> recentFiles() {
        List<Path> result = new ArrayList<>();
        for (String s : ROOT.get("recent", "").split(SEP)) {
            if (!s.isBlank()) {
                result.add(Path.of(s));
            }
        }
        return result;
    }

    public static void addRecentFile(Path file) {
        List<Path> recent = recentFiles();
        Path abs = file.toAbsolutePath().normalize();
        recent.remove(abs);
        recent.add(0, abs);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        ROOT.put("recent", String.join(SEP, recent.stream().map(Path::toString).toList()));
    }

    public static void clearRecentFiles() {
        ROOT.remove("recent");
    }

    // ----------------------------------------------------------- per jar classpath

    private static String jarKey(Path jar) {
        return Integer.toHexString(jar.toAbsolutePath().normalize().toString().hashCode());
    }

    private static Preferences jarNode(Path jar) {
        return ROOT.node("jars").node(jarKey(jar));
    }

    /** Stored classpath settings of a jar, or null if none were saved. */
    public static ClasspathConfig loadClasspath(Path jar) {
        ClasspathConfig cfg = new ClasspathConfig();
        try {
            if (!ROOT.node("jars").nodeExists(jarKey(jar))) {
                return null;
            }
        } catch (BackingStoreException e) {
            return null;
        }
        Preferences node = jarNode(jar);
        cfg.includeNestedJars = node.getBoolean("nested", true);
        cfg.useMaven = node.getBoolean("maven", true);
        cfg.embeddedPom = emptyToNull(node.get("embeddedPom", null));
        String external = emptyToNull(node.get("externalPom", null));
        cfg.externalPom = external == null ? null : Path.of(external);
        cfg.customEntries = paths(node.get("custom", ""));
        List<Path> resolved = paths(node.get("resolved", ""));
        if (node.getBoolean("resolvedValid", false) && resolved.stream().allMatch(Files::exists)) {
            cfg.mavenClasspath = resolved;
        }
        cfg.release = node.getInt("release", 0);
        cfg.javacOptions = node.get("javacOptions", "");
        return cfg;
    }

    public static void saveClasspath(Path jar, ClasspathConfig cfg) {
        Preferences node = jarNode(jar);
        node.putBoolean("nested", cfg.includeNestedJars);
        node.putBoolean("maven", cfg.useMaven);
        node.put("embeddedPom", cfg.embeddedPom == null ? "" : cfg.embeddedPom);
        node.put("externalPom", cfg.externalPom == null ? "" : cfg.externalPom.toString());
        node.put("custom", String.join(File.pathSeparator, cfg.customEntries.stream().map(Path::toString).toList()));
        node.putBoolean("resolvedValid", cfg.mavenClasspath != null);
        String resolved = cfg.mavenClasspath == null ? ""
                : String.join(File.pathSeparator, cfg.mavenClasspath.stream().map(Path::toString).toList());
        // Preferences values are limited to 8 KB
        node.put("resolved", resolved.length() < Preferences.MAX_VALUE_LENGTH ? resolved : "");
        if (resolved.length() >= Preferences.MAX_VALUE_LENGTH) {
            node.putBoolean("resolvedValid", false);
        }
        node.putInt("release", cfg.release);
        node.put("javacOptions", cfg.javacOptions == null ? "" : cfg.javacOptions);
    }

    private static List<Path> paths(String joined) {
        List<Path> result = new ArrayList<>();
        Arrays.stream(joined.split(File.pathSeparator)).filter(s -> !s.isBlank()).forEach(s -> result.add(Path.of(s)));
        return result;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
