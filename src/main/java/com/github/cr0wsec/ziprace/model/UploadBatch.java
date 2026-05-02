package com.github.cr0wsec.ziprace.model;

import java.time.Instant;
import java.util.UUID;

public record UploadBatch(
    UUID id,
    BatchStatus status,
    int totalFiles,
    int processedFiles,
    String errorMessage,
    Instant createdAt,
    Instant completedAt
) {}
