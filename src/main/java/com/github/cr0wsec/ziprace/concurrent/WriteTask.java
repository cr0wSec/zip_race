package com.github.cr0wsec.ziprace.concurrent;

import com.github.cr0wsec.ziprace.model.FileEntry;

import java.util.List;
import java.util.UUID;

/**
 * Immutable command pushed by upload virtual threads and consumed by the
 * single writer thread. The entries list is defensively copied to guarantee
 * immutability across thread boundaries.
 */
public record WriteTask(UUID batchId, List<FileEntry> entries) {
    public WriteTask {
        entries = List.copyOf(entries);  // defensive copy
    }
}