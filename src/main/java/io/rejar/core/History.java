package io.rejar.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Modification history embedded in a jar produced by ReJar ({@value HistoryStore#HISTORY_FILE}).
 * Every save appends one {@link ChangeSet}; original and modified binaries plus sources are stored next to it so any
 * change can be reverted later.
 */
public final class History {

    public int formatVersion = 1;
    public List<ChangeSet> changeSets = new ArrayList<>();

    public enum ChangeType { ADDED, MODIFIED, DELETED }

    public static final class ChangeSet {
        public String id;
        public String timestamp;
        public String user;
        public String description;
        /** File name of the jar this change set was applied to. */
        public String sourceJar;
        public String sourceJarSha256;
        /** Change sets reverted (fully or partially) by this one. */
        public List<String> reverts = new ArrayList<>();
        public List<ChangeEntry> entries = new ArrayList<>();
        public List<SourceChange> sources = new ArrayList<>();

        public String folder() {
            return HistoryStore.CHANGES + id + "/";
        }

        @Override
        public String toString() {
            return id + " - " + (description == null || description.isBlank() ? "(no description)" : description);
        }
    }

    public static final class ChangeEntry {
        public String path;
        public ChangeType type;
        /** Id of the source unit (top-level class) this entry was compiled from, if any. */
        public String unit;
        public String originalSha256;
        public String newSha256;
        /** Location (inside the jar) of the original bytes, null for ADDED entries. */
        public String originalCopy;
        /** Location (inside the jar) of the new bytes, null for DELETED entries. */
        public String modifiedCopy;
        public String revertOf;

        @Override
        public String toString() {
            return type + "  " + path;
        }
    }

    public static final class SourceChange {
        /** Unit id, e.g. {@code BOOT-INF/classes/com/acme/Foo}. */
        public String unit;
        public String className;
        public int release;
        /** Location (inside the jar) of the edited source that was compiled. */
        public String modifiedSource;
        /** Location (inside the jar) of the source as decompiled before editing. */
        public String originalSource;
    }
}
