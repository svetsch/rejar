package io.rejar.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a new jar from a {@link JarModel} and its pending changes. The source jar is never overwritten. The written
 * jar embeds the history of all changes (see {@link HistoryStore}) with the original and new binaries as well as the
 * edited sources, so each change can be reverted later.
 */
public final class JarWriter {

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @param target             written jar
     * @param changeSet          change set recorded in the jar
     * @param signatureRemoved   whether signature files were removed (the jar was signed)
     */
    public record Result(Path target, History.ChangeSet changeSet, boolean signatureRemoved) {
    }

    private JarWriter() {
    }

    public static Result write(JarModel model, Path target, String description) throws IOException {
        Path targetAbs = target.toAbsolutePath().normalize();
        if (targetAbs.equals(model.file()) || (Files.exists(targetAbs) && Files.isSameFile(targetAbs, model.file()))) {
            throw new IOException("The modified jar must be written to a new file, not over " + model.file());
        }
        List<PendingChange> pending = model.pendingList();
        if (pending.isEmpty()) {
            throw new IOException("There is no pending change to save");
        }

        History previous = model.history();
        History history = new History();
        history.formatVersion = previous.formatVersion;
        history.changeSets.addAll(previous.changeSets);

        History.ChangeSet cs = new History.ChangeSet();
        cs.id = String.format("%03d-%s", history.changeSets.size() + 1, LocalDateTime.now().format(ID_FORMAT));
        cs.timestamp = OffsetDateTime.now().toString();
        cs.user = System.getProperty("user.name");
        cs.description = description == null ? "" : description.trim();
        cs.sourceJar = model.name();
        cs.sourceJarSha256 = sha256(model.file());

        // files stored under META-INF/rejar/changes/<id>/
        Map<String, byte[]> historyFiles = new LinkedHashMap<>();
        Set<String> reverts = new LinkedHashSet<>();
        for (PendingChange change : pending) {
            byte[] original = model.readOriginal(change.path());
            History.ChangeEntry entry = new History.ChangeEntry();
            entry.path = change.path();
            entry.unit = change.unitId();
            entry.revertOf = change.revertOf();
            entry.type = change.isDelete() ? History.ChangeType.DELETED
                    : original == null ? History.ChangeType.ADDED : History.ChangeType.MODIFIED;
            entry.originalSha256 = HistoryStore.sha256(original);
            entry.newSha256 = HistoryStore.sha256(change.newBytes());
            if (original != null) {
                entry.originalCopy = HistoryStore.originalCopyPath(cs, change.path());
                historyFiles.put(entry.originalCopy, original);
            }
            if (!change.isDelete()) {
                entry.modifiedCopy = HistoryStore.modifiedCopyPath(cs, change.path());
                historyFiles.put(entry.modifiedCopy, change.newBytes());
            }
            if (change.revertOf() != null) {
                reverts.add(change.revertOf());
            }
            cs.entries.add(entry);
        }
        for (PendingSource source : model.pendingSources().values()) {
            History.SourceChange sc = new History.SourceChange();
            sc.unit = source.unitId();
            sc.className = ClassNames.toFqcn(source.className());
            sc.release = source.release();
            sc.modifiedSource = HistoryStore.modifiedSourcePath(cs, source.unitId());
            historyFiles.put(sc.modifiedSource, source.source().getBytes(StandardCharsets.UTF_8));
            if (source.originalSource() != null) {
                sc.originalSource = HistoryStore.originalSourcePath(cs, source.unitId());
                historyFiles.put(sc.originalSource, source.originalSource().getBytes(StandardCharsets.UTF_8));
            }
            cs.sources.add(sc);
        }
        cs.reverts.addAll(reverts);
        history.changeSets.add(cs);

        boolean stripSignature = model.isSigned();
        Map<String, PendingChange> pendingByPath = model.pending();
        Path dir = targetAbs.getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".rejar-", ".tmp");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
                Set<String> written = new HashSet<>();
                for (ZipEntry original : model.originalEntries()) {
                    String name = original.getName();
                    if (name.equals(HistoryStore.HISTORY_FILE) || (stripSignature && JarModel.isSignatureFile(name))) {
                        continue;
                    }
                    PendingChange change = pendingByPath.get(name);
                    if (change != null) {
                        if (!change.isDelete()) {
                            writeEntry(out, name, change.newBytes(), original);
                        }
                    } else if (stripSignature && name.equalsIgnoreCase(JarFile.MANIFEST_NAME)) {
                        writeEntry(out, name, unsignedManifest(model.readOriginal(name)), original);
                    } else {
                        copyEntry(out, model, original);
                    }
                    written.add(name);
                }
                for (PendingChange change : pending) {
                    if (!change.isDelete() && !written.contains(change.path())) {
                        writeEntry(out, change.path(), change.newBytes(), null);
                        written.add(change.path());
                    }
                }
                for (Map.Entry<String, byte[]> file : historyFiles.entrySet()) {
                    writeEntry(out, file.getKey(), file.getValue(), null);
                }
                writeEntry(out, HistoryStore.HISTORY_FILE, HistoryStore.serialize(history), null);
            }
            Files.move(temp, targetAbs, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new Result(targetAbs, cs, stripSignature);
    }

    private static void copyEntry(ZipOutputStream out, JarModel model, ZipEntry original) throws IOException {
        ZipEntry entry = newEntry(original.getName(), original);
        if (entry.getMethod() == ZipEntry.STORED) {
            entry.setSize(original.getSize());
            entry.setCompressedSize(original.getSize());
            entry.setCrc(original.getCrc());
        }
        out.putNextEntry(entry);
        try (InputStream in = model.zip().getInputStream(original)) {
            in.transferTo(out);
        }
        out.closeEntry();
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data, ZipEntry template) throws IOException {
        ZipEntry entry = newEntry(name, template);
        if (entry.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static ZipEntry newEntry(String name, ZipEntry template) {
        ZipEntry entry = new ZipEntry(name);
        if (template != null) {
            entry.setMethod(template.getMethod() == ZipEntry.STORED ? ZipEntry.STORED : ZipEntry.DEFLATED);
            if (template.getLastModifiedTime() != null) {
                entry.setLastModifiedTime(template.getLastModifiedTime());
            }
            if (template.getComment() != null) {
                entry.setComment(template.getComment());
            }
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
            entry.setTime(System.currentTimeMillis());
        }
        return entry;
    }

    /** Removes per-entry digests from a signed jar's manifest. */
    static byte[] unsignedManifest(byte[] manifestBytes) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
        Iterator<Map.Entry<String, Attributes>> it = manifest.getEntries().entrySet().iterator();
        while (it.hasNext()) {
            Attributes attributes = it.next().getValue();
            List<Object> digests = new ArrayList<>();
            for (Object key : attributes.keySet()) {
                if (key.toString().toLowerCase().contains("-digest")) {
                    digests.add(key);
                }
            }
            digests.forEach(attributes::remove);
            if (attributes.isEmpty()) {
                it.remove();
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        return out.toByteArray();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
