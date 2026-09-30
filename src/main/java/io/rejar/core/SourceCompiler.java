package io.rejar.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles a single edited source file in memory with the JDK compiler. */
public final class SourceCompiler {

    /** A compiler message. Line and column are 1-based, -1 when unknown. */
    public record Problem(Diagnostic.Kind kind, long line, long column, long startOffset, long endOffset,
                          String message) {

        public boolean isError() {
            return kind == Diagnostic.Kind.ERROR;
        }

        @Override
        public String toString() {
            return (line > 0 ? "Line " + line + ": " : "") + kind.name().toLowerCase(Locale.ROOT) + ": " + message;
        }
    }

    /**
     * @param success whether compilation succeeded
     * @param classes internal class name to bytes
     * @param problems errors and warnings
     * @param output raw compiler output
     */
    public record Result(boolean success, Map<String, byte[]> classes, List<Problem> problems, String output) {
    }

    public static boolean isAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /**
     * @param internalName top-level class name, e.g. {@code com/acme/Foo}
     * @param source       java source
     * @param classpath    compilation classpath
     * @param release      value for {@code --release}
     * @param extraOptions additional javac options
     */
    public Result compile(String internalName, String source, List<Path> classpath, int release,
                          List<String> extraOptions) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("No Java compiler available: ReJar must run on a JDK, not a JRE.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        StringWriter out = new StringWriter();

        try (StandardJavaFileManager std = compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, StandardCharsets.UTF_8);
             JavaFileManager fm = new MemoryFileManager(std, outputs)) {
            List<String> options = new ArrayList<>();
            options.add("-g");
            options.add("-proc:none");
            options.add("-Xlint:-options");
            options.add("--release");
            options.add(Integer.toString(release));
            if (!classpath.isEmpty()) {
                options.add("-classpath");
                options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
            }
            options.addAll(extraOptions);
            JavaFileObject unit = new SourceFile(internalName, source);
            boolean ok = compiler.getTask(out, fm, diagnostics, options, null, List.of(unit)).call();

            List<Problem> problems = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                problems.add(new Problem(d.getKind(), d.getLineNumber(), d.getColumnNumber(), d.getStartPosition(),
                        d.getEndPosition(), d.getMessage(Locale.ENGLISH)));
            }
            Map<String, byte[]> classes = new TreeMap<>();
            outputs.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
            return new Result(ok && !classes.isEmpty(), classes, problems, out.toString());
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String internalName, String source) {
            super(URI.create("string:///" + internalName + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class ClassOutput extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes;

        ClassOutput(String internalName, ByteArrayOutputStream bytes) {
            super(URI.create("mem:///" + internalName + Kind.CLASS.extension), Kind.CLASS);
            this.bytes = bytes;
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputs;

        MemoryFileManager(JavaFileManager delegate, Map<String, ByteArrayOutputStream> outputs) {
            super(delegate);
            this.outputs = outputs;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) throws IOException {
            if (kind != JavaFileObject.Kind.CLASS) {
                return super.getJavaFileForOutput(location, className, kind, sibling);
            }
            String internal = className.replace('.', '/');
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            outputs.put(internal, bytes);
            return new ClassOutput(internal, bytes);
        }
    }
}
