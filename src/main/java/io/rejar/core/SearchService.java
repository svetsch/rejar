package io.rejar.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Full-text search in decompiled classes, text resources and entry names. */
public final class SearchService {

    private static final int MAX_RESOURCE_SIZE = 8 * 1024 * 1024;
    private static final int MAX_HITS_PER_FILE = 500;

    public enum Kind { CLASS, RESOURCE, NAME }

    /**
     * @param kind      where the hit was found
     * @param path      unit id for classes, entry path otherwise
     * @param line      1-based line (0 for name hits)
     * @param column    0-based start column within the line
     * @param length    match length
     * @param lineText  line content (trimmed to a reasonable length)
     */
    public record Hit(Kind kind, String path, int line, int column, int length, String lineText) {
    }

    public record Query(String text, boolean regex, boolean caseSensitive, boolean wholeWord,
                        boolean classes, boolean resources, boolean names) {

        public Pattern compile() throws PatternSyntaxException {
            String p = regex ? text : Pattern.quote(text);
            if (wholeWord) {
                p = "\\b(?:" + p + ")\\b";
            }
            int flags = Pattern.MULTILINE | (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            return Pattern.compile(p, flags);
        }
    }

    public interface Progress {
        void update(int done, int total, String current);
    }

    private SearchService() {
    }

    /** Runs the search; hits are streamed to {@code onHit} from worker threads. */
    public static void search(JarModel model, DecompilerService decompiler, Query query, Consumer<Hit> onHit,
                              Progress progress, AtomicBoolean cancel) throws InterruptedException {
        Pattern pattern = query.compile();
        List<String> names = model.entryNames();
        List<ClassUnit> units = query.classes() ? new ArrayList<>(model.units().values()) : List.of();
        List<String> resources = query.resources()
                ? names.stream().filter(n -> !n.endsWith("/") && !ClassNames.isClass(n)).toList()
                : List.of();

        if (query.names()) {
            for (String name : names) {
                Matcher m = pattern.matcher(name);
                if (m.find()) {
                    onHit.accept(new Hit(Kind.NAME, name, 0, m.start(), m.end() - m.start(), name));
                }
            }
        }

        int total = units.size() + resources.size();
        AtomicInteger done = new AtomicInteger();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "rejar-search");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (ClassUnit unit : units) {
                futures.add(pool.submit(() -> {
                    if (!cancel.get()) {
                        progress.update(done.get(), total, unit.fqcn());
                        scan(Kind.CLASS, unit.id(), decompiler.sourceOf(unit), pattern, onHit);
                    }
                    progress.update(done.incrementAndGet(), total, unit.fqcn());
                }));
            }
            for (String resource : resources) {
                futures.add(pool.submit(() -> {
                    if (!cancel.get()) {
                        try {
                            if (model.size(resource) <= MAX_RESOURCE_SIZE) {
                                byte[] data = model.read(resource);
                                if (data != null && TextSupport.isText(data)) {
                                    scan(Kind.RESOURCE, resource, TextSupport.decode(data), pattern, onHit);
                                }
                            }
                        } catch (IOException ignored) {
                            // unreadable entry
                        }
                    }
                    progress.update(done.incrementAndGet(), total, resource);
                }));
            }
            for (Future<?> f : futures) {
                if (cancel.get()) {
                    break;
                }
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // a single failing unit must not abort the search
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void scan(Kind kind, String path, String text, Pattern pattern, Consumer<Hit> onHit) {
        Matcher m = pattern.matcher(text);
        int hits = 0;
        int lineStart = 0;
        int lineNo = 1;
        int scanPos = 0;
        while (m.find() && hits < MAX_HITS_PER_FILE) {
            if (m.end() == m.start()) {
                if (m.end() >= text.length()) {
                    break;
                }
                continue;
            }
            // advance line counters up to match start
            for (int i = scanPos; i < m.start(); i++) {
                if (text.charAt(i) == '\n') {
                    lineNo++;
                    lineStart = i + 1;
                }
            }
            scanPos = m.start();
            int lineEnd = text.indexOf('\n', m.start());
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int column = m.start() - lineStart;
            int length = Math.min(m.end(), lineEnd) - m.start();
            onHit.accept(new Hit(kind, path, lineNo, column, length, line.length() > 400 ? line.substring(0, 400) : line));
            hits++;
        }
    }
}
