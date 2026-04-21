package com.github.cr0wsec.ziprace.concurrent;

import com.github.cr0wsec.ziprace.model.FileEntry;

import java.util.List;
import java.util.UUID;

public record WriteTask(UUID batch, List<FileEntry> entries) {}
