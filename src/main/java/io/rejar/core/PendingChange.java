package io.rejar.core;

/**
 * An unsaved modification of a single jar entry.
 *
 * @param path     entry path
 * @param newBytes new content, {@code null} when the entry is deleted
 * @param unitId   id of the source unit the entry was compiled from (null for resources)
 * @param reason   human readable origin (compile, resource edit, revert...)
 * @param revertOf id of the change set this change reverts, or null
 */
public record PendingChange(String path, byte[] newBytes, String unitId, String reason, String revertOf) {

    public boolean isDelete() {
        return newBytes == null;
    }
}
