package com.github.cr0wsec.ziprace.model;

import java.util.UUID;

public record FileEntry(
    long id,
    UUID batchId,
    String fileName,
    long fileSize
) {}
