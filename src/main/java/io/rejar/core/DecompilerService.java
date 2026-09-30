package io.rejar.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

/**
 * Decompiles class units with Vineflower. Each unit is decompiled on its own; referenced classes (from the jar, the
 * compilation classpath or the JDK) are loaded lazily only when the decompiler asks for them.
 */
public final class DecompilerService {

    private final JarModel model;
    private final Map<String, CachedSource> cache = new ConcurrentHashMap<>();
    private volatile ClassLookup extraLibraries = name -> null;

    private record CachedSource(long revision, String source) {
    }

    /** Resolves class bytes from outside the jar (compilation classpath). */
    @FunctionalInterface
    public interface ClassLookup {
        byte[] find(String internalName) throws IOException;
    }

    public DecompilerService(JarModel model) {
        this.model = model;
        model.addChangeListener(paths -> {
            for (String path : paths) {
                if (ClassNames.isClass(path)) {
                    // drop the unit owning the entry, whether the entry is the outer class or a nested one
                    String id = ClassNames.prefixOf(path) + ClassNames.internalName(path);
                    cache.keySet().removeIf(k -> k.equals(id) || id.startsWith(k + "$") || k.startsWith(id + "$"));
                }
            }
        });
    }

    public void setExtraLibraries(ClassLookup lookup) {
        this.extraLibraries = lookup == null ? name -> null : lookup;
    }

    public boolean isCached(ClassUnit unit) {
        return cache.containsKey(unit.id());
    }

    /**
     * Source to display/search for a unit: the edited source when the unit was modified and compiled, otherwise the
     * decompiled source of the current bytes.
     */
    public String sourceOf(ClassUnit unit) {
        PendingSource edited = model.pendingSource(unit.id());
        if (edited != null) {
            return edited.source();
        }
        return decompile(unit);
    }

    /** Decompiles the current bytes of the unit (cached). */
    public String decompile(ClassUnit unit) {
        CachedSource cached = cache.get(unit.id());
        if (cached != null) {
            return cached.source();
        }
        String source = doDecompile(unit);
        cache.put(unit.id(), new CachedSource(model.revision(), source));
        return source;
    }

    private String doDecompile(ClassUnit unit) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try {
            for (String entry : unit.entries()) {
                byte[] bytes = model.read(entry);
                if (bytes != null) {
                    classes.put(ClassNames.internalName(entry), bytes);
                }
            }
        } catch (IOException e) {
            return errorComment("Cannot read class " + unit.fqcn(), e);
        }
        if (classes.isEmpty()) {
            return "// " + unit.fqcn() + " has no class file";
        }

        Map<String, String> results = new LinkedHashMap<>();
        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        options.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
        options.put(IFernflowerPreferences.INDENT_STRING, "    ");
        options.put(IFernflowerPreferences.NEW_LINE_SEPARATOR, "1");
        options.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, "20");
        options.put(IFernflowerPreferences.THREADS, "1");
        options.put(IFernflowerPreferences.LOG_LEVEL, "error");
        StringBuilder errors = new StringBuilder();
        IFernflowerLogger logger = new IFernflowerLogger() {
            @Override
            public void writeMessage(String message, Severity severity) {
                if (severity == Severity.ERROR) {
                    errors.append(message).append('\n');
                }
            }

            @Override
            public void writeMessage(String message, Severity severity, Throwable t) {
                writeMessage(message + ": " + t, severity);
            }
        };
        logger.setSeverity(IFernflowerLogger.Severity.ERROR);

        Fernflower fernflower = new Fernflower(new NoOpSaver(), options, logger);
        try {
            fernflower.addSource(new UnitSource(unit, classes, results));
            fernflower.addLibrary(new LibrarySource(unit, classes.keySet()));
            fernflower.decompileContext();
        } catch (Throwable t) {
            return errorComment("Decompilation of " + unit.fqcn() + " failed", t);
        } finally {
            fernflower.clearContext();
        }
        String source = results.get(unit.className());
        if (source == null && !results.isEmpty()) {
            source = String.join("\n", results.values());
        }
        if (source == null) {
            return "// Decompilation of " + unit.fqcn() + " produced no output\n" + comment(errors.toString());
        }
        return source;
    }

    private static String errorComment(String title, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return "// " + title + "\n" + comment(sw.toString());
    }

    private static String comment(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                sb.append("// ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** The classes to decompile. */
    private static final class UnitSource implements IContextSource {
        private final ClassUnit unit;
        private final Map<String, byte[]> classes;
        private final Map<String, String> results;

        UnitSource(ClassUnit unit, Map<String, byte[]> classes, Map<String, String> results) {
            this.unit = unit;
            this.classes = classes;
            this.results = results;
        }

        @Override
        public String getName() {
            return unit.fqcn();
        }

        @Override
        public Entries getEntries() {
            List<Entry> entries = new ArrayList<>();
            classes.keySet().forEach(name -> entries.add(Entry.atBase(name)));
            return new Entries(entries, List.of(), List.of());
        }

        @Override
        public byte[] getClassBytes(String className) {
            return classes.get(className);
        }

        @Override
        public InputStream getInputStream(String resource) {
            if (resource.endsWith(CLASS_SUFFIX)) {
                byte[] bytes = classes.get(resource.substring(0, resource.length() - CLASS_SUFFIX.length()));
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
            return null;
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override
                public void begin() {
                }

                @Override
                public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                    if (content != null) {
                        synchronized (results) {
                            results.put(qualifiedName, content);
                        }
                    }
                }

                @Override
                public void acceptDirectory(String directory) {
                }

                @Override
                public void acceptOther(String path) {
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /** Lazily resolves referenced classes from the jar, the extra libraries and the running JDK. */
    private final class LibrarySource implements IContextSource {
        private final ClassUnit unit;
        private final java.util.Set<String> own;

        LibrarySource(ClassUnit unit, java.util.Set<String> own) {
            this.unit = unit;
            this.own = own;
        }

        @Override
        public String getName() {
            return "rejar-library";
        }

        @Override
        public boolean isLazy() {
            return true;
        }

        @Override
        public Entries getEntries() {
            return Entries.EMPTY;
        }

        @Override
        public byte[] getClassBytes(String className) throws IOException {
            if (own.contains(className)) {
                return null;
            }
            byte[] bytes = model.read(unit.prefix() + className + CLASS_SUFFIX);
            if (bytes == null && !unit.prefix().isEmpty()) {
                bytes = model.read(className + CLASS_SUFFIX);
            }
            if (bytes == null) {
                bytes = extraLibraries.find(className);
            }
            if (bytes == null) {
                try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(className + CLASS_SUFFIX)) {
                    bytes = in == null ? null : in.readAllBytes();
                }
            }
            return bytes;
        }

        @Override
        public boolean hasClass(String className) throws IOException {
            return getClassBytes(className) != null;
        }

        @Override
        public InputStream getInputStream(String resource) throws IOException {
            if (!resource.endsWith(CLASS_SUFFIX)) {
                return null;
            }
            byte[] bytes = getClassBytes(resource.substring(0, resource.length() - CLASS_SUFFIX.length()));
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    private static final class NoOpSaver implements IResultSaver {
        @Override
        public void saveFolder(String path) {
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }
    }
}
