package io.rejar.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Layout and (de)serialization of the history embedded in modified jars. */
public final class HistoryStore {

    public static final String ROOT = "META-INF/rejar/";
    public static final String HISTORY_FILE = ROOT + "history.json";
    public static final String CHANGES = ROOT + "changes/";
    /**
     * Suffix appended to stored copies of class files, so class-path scanners never mistake a backup for a real class.
     */
    public static final String BINARY_SUFFIX = ".bin";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private HistoryStore() {
    }

    public static History parse(byte[] json) {
        if (json == null) {
            return new History();
        }
        History history = GSON.fromJson(new String(json, StandardCharsets.UTF_8), History.class);
        return history == null ? new History() : history;
    }

    public static byte[] serialize(History history) {
        return GSON.toJson(history).getBytes(StandardCharsets.UTF_8);
    }

    public static String originalCopyPath(History.ChangeSet cs, String entryPath) {
        return cs.folder() + "original/" + entryPath + BINARY_SUFFIX;
    }

    public static String modifiedCopyPath(History.ChangeSet cs, String entryPath) {
        return cs.folder() + "modified/" + entryPath + BINARY_SUFFIX;
    }

    public static String modifiedSourcePath(History.ChangeSet cs, String unitId) {
        return cs.folder() + "src/" + unitId + ".java";
    }

    public static String originalSourcePath(History.ChangeSet cs, String unitId) {
        return cs.folder() + "src-original/" + unitId + ".java";
    }

    public static String sha256(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
