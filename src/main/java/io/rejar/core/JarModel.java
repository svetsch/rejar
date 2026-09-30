package io.rejar.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An opened jar: the original (read-only) content plus an overlay of pending, unsaved changes.
 * The original file is never modified; {@link JarWriter} produces a new jar from this model.
 */
public final class JarModel implements Closeable {

    private final Path file;
    private final ZipFile zip;
    private final Map<String, ZipEntry> originalEntries = new LinkedHashMap<>();
    private final History history;

    private final Map<String, PendingChange> pending = new TreeMap<>();
    private final Map<String, PendingSource> pendingSources = new TreeMap<>();
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();

    private long revision;
    private List<String> effectiveNamesCache;
    private Set<String> effectiveSetCache;
    private Map<String, ClassUnit> unitsCache;
    private Map<String, String> unitByEntryCache;
    private Path workDir;

    private JarModel(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.zip = new ZipFile(this.file.toFile());
        zip.stream().forEach(e -> originalEntries.putIfAbsent(e.getName(), e));
        ZipEntry historyEntry = originalEntries.get(HistoryStore.HISTORY_FILE);
        this.history = HistoryStore.parse(historyEntry == null ? null : readZip(historyEntry));
    }

    public static JarModel open(Path file) throws IOException {
        return new JarModel(file);
    }

    public Path file() {
        return file;
    }

    public String name() {
        return file.getFileName().toString();
    }

    public History history() {
        return history;
    }

    public ZipFile zip() {
        return zip;
    }

    // ------------------------------------------------------------------ reading

    public synchronized long revision() {
        return revision;
    }

    /** Entry names after applying pending changes, in jar order (added entries last). */
    public synchronized List<String> entryNames() {
        if (effectiveNamesCache == null) {
            List<String> names = new ArrayList<>(originalEntries.size() + pending.size());
            for (String name : originalEntries.keySet()) {
                PendingChange change = pending.get(name);
                if (change == null || !change.isDelete()) {
                    names.add(name);
                }
            }
            for (PendingChange change : pending.values()) {
                if (!change.isDelete() && !originalEntries.containsKey(change.path())) {
                    names.add(change.path());
                }
            }
            effectiveNamesCache = Collections.unmodifiableList(names);
            effectiveSetCache = Collections.unmodifiableSet(new HashSet<>(names));
        }
        return effectiveNamesCache;
    }

    public synchronized boolean exists(String name) {
        entryNames();
        return effectiveSetCache.contains(name);
    }

    public boolean existsInOriginal(String name) {
        return originalEntries.containsKey(name);
    }

    public ZipEntry originalEntry(String name) {
        return originalEntries.get(name);
    }

    public Collection<ZipEntry> originalEntries() {
        return originalEntries.values();
    }

    /** Current content (pending change if any, otherwise original). Null if the entry does not exist. */
    public byte[] read(String name) throws IOException {
        PendingChange change;
        synchronized (this) {
            change = pending.get(name);
        }
        if (change != null) {
            return change.newBytes();
        }
        return readOriginal(name);
    }

    public byte[] readOriginal(String name) throws IOException {
        ZipEntry entry = originalEntries.get(name);
        return entry == null ? null : readZip(entry);
    }

    public long size(String name) {
        PendingChange change;
        synchronized (this) {
            change = pending.get(name);
        }
        if (change != null) {
            return change.isDelete() ? -1 : change.newBytes().length;
        }
        ZipEntry entry = originalEntries.get(name);
        return entry == null ? -1 : entry.getSize();
    }

    private byte[] readZip(ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    public boolean isSigned() {
        return originalEntries.keySet().stream().anyMatch(JarModel::isSignatureFile);
    }

    public static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        String upper = name.toUpperCase();
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || (upper.startsWith("META-INF/SIG-") && !upper.endsWith("/"));
    }

    /** Maven descriptors embedded by the maven-jar-plugin ({@code META-INF/maven/g/a/pom.xml}). */
    public List<String> embeddedPoms() {
        return originalEntries.keySet().stream()
                .filter(n -> n.startsWith("META-INF/maven/") && n.endsWith("/pom.xml"))
                .sorted()
                .toList();
    }

    /** Jars nested in this jar (Spring Boot {@code BOOT-INF/lib}, war {@code WEB-INF/lib}, ...). */
    public List<String> nestedJars() {
        return entryNames().stream().filter(n -> n.toLowerCase().endsWith(".jar")).toList();
    }

    // ------------------------------------------------------------------ class units

    public synchronized Map<String, ClassUnit> units() {
        if (unitsCache == null) {
            List<String> names = entryNames();
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            Map<String, String> byEntry = new LinkedHashMap<>();
            for (String name : names) {
                if (ClassNames.isClass(name)) {
                    String id = ClassNames.unitIdOf(name, effectiveSetCache);
                    grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(name);
                    byEntry.put(name, id);
                }
            }
            Map<String, ClassUnit> units = new LinkedHashMap<>();
            grouped.forEach((id, entries) -> {
                String outer = id + ClassNames.CLASS_SUFFIX;
                entries.sort(Comparator.comparing((String e) -> !e.equals(outer)).thenComparing(Comparator.naturalOrder()));
                String prefix = ClassNames.prefixOf(outer);
                units.put(id, new ClassUnit(id, prefix, id.substring(prefix.length()), List.copyOf(entries)));
            });
            unitsCache = Collections.unmodifiableMap(units);
            unitByEntryCache = byEntry;
        }
        return unitsCache;
    }

    public synchronized ClassUnit unit(String id) {
        return units().get(id);
    }

    public synchronized ClassUnit unitForEntry(String entry) {
        units();
        String id = unitByEntryCache.get(entry);
        return id == null ? null : unitsCache.get(id);
    }

    // ------------------------------------------------------------------ pending changes

    public synchronized Map<String, PendingChange> pending() {
        return Map.copyOf(pending);
    }

    public synchronized List<PendingChange> pendingList() {
        return List.copyOf(pending.values());
    }

    public synchronized boolean hasPendingChanges() {
        return !pending.isEmpty();
    }

    public synchronized boolean isModified(String path) {
        return pending.containsKey(path);
    }

    public synchronized boolean isUnitModified(String unitId) {
        return pendingSources.containsKey(unitId)
                || pending.values().stream().anyMatch(p -> unitId.equals(p.unitId()));
    }

    public synchronized PendingSource pendingSource(String unitId) {
        return pendingSources.get(unitId);
    }

    public synchronized Map<String, PendingSource> pendingSources() {
        return Map.copyOf(pendingSources);
    }

    /**
     * Replaces the binaries of a unit with freshly compiled classes.
     *
     * @param unit     the unit that was edited
     * @param compiled internal class name to class bytes, as produced by the compiler
     */
    public void applyCompiled(ClassUnit unit, Map<String, byte[]> compiled, String source, String originalSource,
                              int release) {
        Set<String> changed = new LinkedHashSet<>();
        synchronized (this) {
            // start from the original state of this unit
            pending.values().removeIf(p -> {
                if (unit.id().equals(p.unitId()) && p.revertOf() == null) {
                    changed.add(p.path());
                    return true;
                }
                return false;
            });
            invalidate();
            Set<String> previous = new LinkedHashSet<>(unit.entries());
            ClassUnit restored = units().get(unit.id());
            if (restored != null) {
                previous.addAll(restored.entries());
            }
            Set<String> produced = new LinkedHashSet<>();
            for (Map.Entry<String, byte[]> e : compiled.entrySet()) {
                String path = unit.prefix() + e.getKey() + ClassNames.CLASS_SUFFIX;
                produced.add(path);
                putPending(new PendingChange(path, e.getValue(), unit.id(), "compiled " + unit.fqcn(), null));
                changed.add(path);
            }
            for (String entry : previous) {
                if (!produced.contains(entry)) {
                    putPending(new PendingChange(entry, null, unit.id(), "compiled " + unit.fqcn(), null));
                    changed.add(entry);
                }
            }
            pendingSources.put(unit.id(), new PendingSource(unit.id(), unit.className(), source, originalSource, release));
            invalidate();
        }
        fire(changed);
    }

    /** Replaces (or adds) a resource. */
    public void putResource(String path, byte[] content, String reason) {
        synchronized (this) {
            putPending(new PendingChange(path, content, null, reason, null));
            invalidate();
        }
        fire(Set.of(path));
    }

    public void deleteEntry(String path) {
        synchronized (this) {
            String unitId = ClassNames.isClass(path) ? unitIdFor(path) : null;
            putPending(new PendingChange(path, null, unitId, "deleted", null));
            invalidate();
        }
        fire(Set.of(path));
    }

    /** Drops pending changes of the given entries. */
    public void discard(Collection<String> paths) {
        Set<String> changed = new LinkedHashSet<>();
        synchronized (this) {
            for (String path : paths) {
                PendingChange removed = pending.remove(path);
                if (removed != null) {
                    changed.add(path);
                    if (removed.unitId() != null && pending.values().stream().noneMatch(p -> removed.unitId().equals(p.unitId()))) {
                        pendingSources.remove(removed.unitId());
                    }
                }
            }
            invalidate();
        }
        fire(changed);
    }

    /** Drops all pending changes of a unit (its binaries go back to their state in this jar). */
    public void discardUnit(String unitId) {
        List<String> paths;
        synchronized (this) {
            paths = pending.values().stream().filter(p -> unitId.equals(p.unitId())).map(PendingChange::path).toList();
            pendingSources.remove(unitId);
        }
        discard(paths);
        fire(Set.of(unitId + ClassNames.CLASS_SUFFIX));
    }

    public void discardAll() {
        Set<String> changed;
        synchronized (this) {
            changed = new LinkedHashSet<>(pending.keySet());
            pending.clear();
            pendingSources.clear();
            invalidate();
        }
        fire(changed);
    }

    /**
     * Stages the revert of (a subset of) a change set recorded in this jar's history: entries get their original
     * bytes back (or are removed if they were added).
     *
     * @return paths whose current content differs from what the change set produced (changed again later)
     */
    public List<String> stageRevert(History.ChangeSet cs, Collection<History.ChangeEntry> entries) throws IOException {
        List<String> conflicts = new ArrayList<>();
        List<PendingChange> changes = new ArrayList<>();
        for (History.ChangeEntry e : entries) {
            byte[] current = read(e.path);
            String expected = e.type == History.ChangeType.DELETED ? null : e.newSha256;
            if (!Objects.equals(HistoryStore.sha256(current), expected)) {
                conflicts.add(e.path);
            }
            byte[] target = null;
            if (e.type != History.ChangeType.ADDED) {
                target = read(e.originalCopy);
                if (target == null) {
                    throw new IOException("Original copy missing in jar: " + e.originalCopy);
                }
            }
            String unitId = e.unit != null ? e.unit : (ClassNames.isClass(e.path) ? unitIdFor(e.path) : null);
            changes.add(new PendingChange(e.path, target, unitId, "revert of " + cs.id, cs.id));
        }
        Set<String> changed = new LinkedHashSet<>();
        synchronized (this) {
            for (PendingChange change : changes) {
                putPending(change);
                changed.add(change.path());
                if (change.unitId() != null) {
                    pendingSources.remove(change.unitId());
                }
            }
            invalidate();
        }
        fire(changed);
        return conflicts;
    }

    private void putPending(PendingChange change) {
        ZipEntry original = originalEntries.get(change.path());
        if (change.isDelete() ? original == null : original != null && sameAsOriginal(original, change.newBytes())) {
            pending.remove(change.path());
        } else {
            pending.put(change.path(), change);
        }
    }

    private boolean sameAsOriginal(ZipEntry original, byte[] bytes) {
        if (original.getSize() >= 0 && original.getSize() != bytes.length) {
            return false;
        }
        try {
            return Arrays.equals(readZip(original), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String unitIdFor(String path) {
        entryNames();
        Set<String> all = new HashSet<>(effectiveSetCache);
        all.addAll(originalEntries.keySet());
        return ClassNames.unitIdOf(path, all);
    }

    private void invalidate() {
        revision++;
        effectiveNamesCache = null;
        effectiveSetCache = null;
        unitsCache = null;
        unitByEntryCache = null;
    }

    // ------------------------------------------------------------------ listeners

    /** Registers a listener notified with the changed entry paths (called on the mutating thread). */
    public void addChangeListener(Consumer<Set<String>> listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Consumer<Set<String>> listener) {
        listeners.remove(listener);
    }

    private void fire(Set<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        Set<String> copy = Set.copyOf(changed);
        for (Consumer<Set<String>> listener : listeners) {
            listener.accept(copy);
        }
    }

    // ------------------------------------------------------------------ working directory

    /** Temporary directory owned by this model (deleted on close). */
    public synchronized Path workDir() throws IOException {
        if (workDir == null) {
            workDir = Files.createTempDirectory("rejar-");
        }
        return workDir;
    }

    @Override
    public void close() throws IOException {
        zip.close();
        Path dir;
        synchronized (this) {
            dir = workDir;
            workDir = null;
        }
        if (dir != null) {
            deleteRecursively(dir);
        }
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
