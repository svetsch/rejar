package io.rejar.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarRoundTripTest {

    @TempDir
    Path tmp;

    private static final String GREETER = """
            package demo;

            public class Greeter {
                private final String name;

                public Greeter(String name) {
                    this.name = name;
                }

                public String greet() {
                    Runnable r = new Runnable() {
                        public void run() {
                        }
                    };
                    r.run();
                    return new Helper().prefix() + name;
                }

                public static class Helper {
                    public String prefix() {
                        return "Hello ";
                    }
                }
            }
            """;

    private static final String MAIN = """
            package demo;

            public class Main {
                public static String run() {
                    return new Greeter("world").greet();
                }
            }
            """;

    /** Builds a jar from sources, optionally placing classes under a prefix (e.g. BOOT-INF/classes/). */
    private Path buildJar(String prefix) throws IOException {
        SourceCompiler compiler = new SourceCompiler();
        SourceCompiler.Result greeter = compiler.compile("demo/Greeter", GREETER, List.of(), 17, List.of());
        assertTrue(greeter.success(), greeter.problems().toString());
        Path jar = tmp.resolve(prefix.isEmpty() ? "demo.jar" : "boot.jar");
        Path greeterDir = tmp.resolve("classes-" + prefix.hashCode());
        for (Map.Entry<String, byte[]> e : greeter.classes().entrySet()) {
            Path f = greeterDir.resolve(e.getKey() + ".class");
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
        SourceCompiler.Result main = compiler.compile("demo/Main", MAIN, List.of(greeterDir), 17, List.of());
        assertTrue(main.success(), main.problems().toString());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("config/app.properties"));
            out.write("greeting=hello\nkey=value\n".getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : greeter.classes().entrySet()) {
                out.putNextEntry(new ZipEntry(prefix + e.getKey() + ".class"));
                out.write(e.getValue());
            }
            for (Map.Entry<String, byte[]> e : main.classes().entrySet()) {
                out.putNextEntry(new ZipEntry(prefix + e.getKey() + ".class"));
                out.write(e.getValue());
            }
        }
        return jar;
    }

    @Test
    void unitsGroupNestedClasses() throws IOException {
        try (JarModel model = JarModel.open(buildJar(""))) {
            Map<String, ClassUnit> units = model.units();
            assertEquals(2, units.size(), units.keySet().toString());
            ClassUnit greeter = units.get("demo/Greeter");
            assertEquals(List.of("demo/Greeter.class", "demo/Greeter$1.class", "demo/Greeter$Helper.class"), greeter.entries());
        }
    }

    @Test
    void decompileEditCompileSaveAndRevert() throws Exception {
        Path jar = buildJar("");
        byte[] originalGreeter;
        Path patched = tmp.resolve("demo-patched.jar");
        try (JarModel model = JarModel.open(jar)) {
            originalGreeter = model.read("demo/Greeter.class");
            DecompilerService decompiler = new DecompilerService(model);
            ClassUnit unit = model.unit("demo/Greeter");
            String source = decompiler.decompile(unit);
            assertTrue(source.contains("class Greeter"), source);
            assertTrue(source.contains("class Helper"), source);
            assertTrue(source.contains("\"Hello \""), source);

            // edit: new greeting and drop the anonymous class
            String edited = source.replace("\"Hello \"", "\"Bonjour \"");
            edited = edited.replaceAll("(?s)Runnable r = new Runnable\\(\\) \\{.*?};\\s*r\\.run\\(\\);", "");
            ClasspathBuilder cpb = new ClasspathBuilder(model);
            SourceCompiler.Result result = new SourceCompiler().compile(unit.className(), edited,
                    cpb.build(new ClasspathConfig()), 17, List.of());
            assertTrue(result.success(), result.problems() + "\n" + edited);
            model.applyCompiled(unit, result.classes(), edited, source, 17);

            assertTrue(model.isModified("demo/Greeter.class"));
            assertFalse(model.exists("demo/Greeter$1.class"), "stale anonymous class must be removed");
            assertTrue(decompiler.sourceOf(unit).contains("Bonjour"));

            // the original file can never be the target
            assertThrows(IOException.class, () -> JarWriter.write(model, jar, "overwrite"));
            JarWriter.write(model, patched, "French greeting");
        }
        assertEquals("Bonjour world", runMain(patched));
        assertEquals("Hello world", runMain(jar));

        Path reverted = tmp.resolve("demo-reverted.jar");
        try (JarModel model = JarModel.open(patched)) {
            History history = model.history();
            assertEquals(1, history.changeSets.size());
            History.ChangeSet cs = history.changeSets.get(0);
            assertEquals("French greeting", cs.description);
            assertEquals(1, cs.sources.size());
            String storedSource = new String(model.read(cs.sources.get(0).modifiedSource), StandardCharsets.UTF_8);
            assertTrue(storedSource.contains("Bonjour"));
            assertNotNull(model.read(cs.sources.get(0).originalSource));
            History.ChangeEntry deleted = cs.entries.stream().filter(e -> e.path.equals("demo/Greeter$1.class")).findFirst().orElseThrow();
            assertEquals(History.ChangeType.DELETED, deleted.type);
            assertTrue(deleted.originalCopy.endsWith(".class.bin"));

            List<String> conflicts = model.stageRevert(cs, cs.entries);
            assertTrue(conflicts.isEmpty(), conflicts.toString());
            assertArrayEquals(originalGreeter, model.read("demo/Greeter.class"));
            assertTrue(model.exists("demo/Greeter$1.class"));
            JarWriter.write(model, reverted, "undo");
        }
        assertEquals("Hello world", runMain(reverted));
        try (JarModel model = JarModel.open(reverted)) {
            assertEquals(2, model.history().changeSets.size());
            assertEquals(List.of(model.history().changeSets.get(0).id), model.history().changeSets.get(1).reverts);
            assertArrayEquals(originalGreeter, model.read("demo/Greeter.class"));
        }
    }

    @Test
    void springBootLayoutKeepsPrefix() throws Exception {
        Path jar = buildJar("BOOT-INF/classes/");
        try (JarModel model = JarModel.open(jar)) {
            ClassUnit unit = model.unit("BOOT-INF/classes/demo/Greeter");
            assertNotNull(unit);
            assertEquals("demo/Greeter", unit.className());
            DecompilerService decompiler = new DecompilerService(model);
            String source = decompiler.decompile(unit);
            assertTrue(source.contains("package demo;"), source);
            String edited = source.replace("\"Hello \"", "\"Hi \"");
            SourceCompiler.Result result = new SourceCompiler().compile(unit.className(), edited,
                    new ClasspathBuilder(model).build(new ClasspathConfig()), 17, List.of());
            assertTrue(result.success(), result.problems().toString());
            model.applyCompiled(unit, result.classes(), edited, source, 17);
            assertTrue(model.isModified("BOOT-INF/classes/demo/Greeter.class"));
            assertNull(model.pending().get("demo/Greeter.class"));
        }
    }

    @Test
    void searchFindsDecompiledCodeAndResources() throws Exception {
        try (JarModel model = JarModel.open(buildJar(""))) {
            DecompilerService decompiler = new DecompilerService(model);
            List<SearchService.Hit> hits = new ArrayList<>();
            SearchService.search(model, decompiler,
                    new SearchService.Query("hello", false, false, false, true, true, true),
                    h -> {
                        synchronized (hits) {
                            hits.add(h);
                        }
                    }, (d, t, c) -> {
                    }, new AtomicBoolean());
            assertTrue(hits.stream().anyMatch(h -> h.kind() == SearchService.Kind.CLASS && h.path().equals("demo/Greeter")), hits.toString());
            assertTrue(hits.stream().anyMatch(h -> h.kind() == SearchService.Kind.RESOURCE && h.line() == 1), hits.toString());
        }
    }

    @Test
    void signedManifestLosesDigests() throws IOException {
        String manifest = "Manifest-Version: 1.0\r\nMain-Class: demo.Main\r\n\r\nName: demo/Main.class\r\nSHA-256-Digest: abc=\r\n\r\n";
        String out = new String(JarWriter.unsignedManifest(manifest.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertTrue(out.contains("Main-Class: demo.Main"));
        assertFalse(out.contains("Digest"));
    }

    private static String runMain(Path jar) throws Exception {
        try (JarFile ignored = new JarFile(jar.toFile());
             URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return (String) cl.loadClass("demo.Main").getMethod("run").invoke(null);
        }
    }
}
