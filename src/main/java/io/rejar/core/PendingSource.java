package io.rejar.core;

/**
 * Edited source of a unit that was successfully compiled and applied (not yet saved).
 *
 * @param unitId         unit id
 * @param className      internal top-level class name
 * @param source         edited source that was compiled
 * @param originalSource source as decompiled before editing
 * @param release        javac --release used
 */
public record PendingSource(String unitId, String className, String source, String originalSource, int release) {
}
