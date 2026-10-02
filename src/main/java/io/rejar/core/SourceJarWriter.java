package io.rejar.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Writes the source jar ({@code <name>-sources.jar}) of a {@link JarModel}: every class unit is decompiled (or taken
 * from its edited source when it has a pending change) and stored as a {@code .java} file under its package path, so
 * the result can be attached as sources in an IDE or published next to the binary jar.
 */
public final class SourceJarWriter {

    private static final String VERSIONS_ROOT = "META-INF/versions/";

    /**
     * @param target    written source jar
     * @param sources   number of {@code .java} files written
     * @param cancelled whether the operation was cancelled: nothing was written to {@code target}
     */
    public record Result(Path target, int sources, boolean cancelled) {
    }

    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    private SourceJarWriter() {
    }

    /** Conventional file name of the source jar of a jar, e.g. {@code app-1.0.jar} gives {@code app-1.0-sources.jar}. */
    public static String defaultName(String jarName) {
        return jarName.replaceFirst("\\.[^.]+$", "") + "-sources.jar";
    }

    /**
     * @param cancel checked before each class is decompiled; classes being decompiled at that time are finished first
     */
    public static Result write(JarModel model, DecompilerService decompiler, Path target, Progress progress,
                               AtomicBoolean cancel) throws IOException {
        Path targetAbs = target.toAbsolutePath().normalize();
        if (targetAbs.equals(model.file()) || (Files.exists(targetAbs) && Files.isSameFile(targetAbs, model.file()))) {
            throw new IOException("The source jar must be written to a new file, not over " + model.file());
        }
        List<ClassUnit> units = new ArrayList<>(model.units().values());

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Created-By", "ReJar");
        if (units.stream().anyMatch(u -> u.prefix().startsWith(VERSIONS_ROOT))) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }

        Path dir = targetAbs.getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".rejar-", ".tmp");
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        try {
            Set<String> written = new HashSet<>();
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temp), manifest)) {
                AtomicInteger done = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (ClassUnit unit : units) {
                    futures.add(pool.submit(() -> {
                        if (cancel.get()) {
                            return null;
                        }
                        byte[] source = decompiler.sourceOf(unit).getBytes(StandardCharsets.UTF_8);
                        String name = entryName(unit);
                        synchronized (out) {
                            // the same class may exist under several class roots: the first one wins
                            if (written.add(name)) {
                                out.putNextEntry(new ZipEntry(name));
                                out.write(source);
                                out.closeEntry();
                            }
                        }
                        progress.update(done.incrementAndGet(), units.size());
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while writing " + targetAbs, e);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            if (cancel.get()) {
                return new Result(targetAbs, 0, true);
            }
            Files.move(temp, targetAbs, StandardCopyOption.REPLACE_EXISTING);
            return new Result(targetAbs, written.size(), false);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Sources are stored at the root of the jar; only multi-release variants keep their {@code META-INF/versions/N/}. */
    private static String entryName(ClassUnit unit) {
        return unit.prefix().startsWith(VERSIONS_ROOT) ? unit.prefix() + unit.sourcePath() : unit.sourcePath();
    }
}
