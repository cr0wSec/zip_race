package com.github.cr0wsec.ziprace.concurrent;

import com.github.cr0wsec.ziprace.model.FileEntry;
import com.github.cr0wsec.ziprace.repository.BatchRepository;
import com.github.cr0wsec.ziprace.repository.FileEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DatabaseWriter#processTask(WriteTask)}.
 * <p>
 * Visibility of {@code processTask} is package-private (rather than private)
 * specifically to enable direct testing without reflection. The tests cover the
 * two distinct paths: nominal completion and integrity-check failure.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseWriterTest {

    @Mock
    BlockingQueue<WriteTask> queue;

    @Mock
    BatchRepository batchRepository;

    @Mock
    FileEntryRepository fileEntryRepository;

    DatabaseWriter writer;

    @BeforeEach
    void setUp() {
        // Manual instantiation: @InjectMocks cannot resolve the primitive
        // shutdownTimeoutMs parameter. The value is irrelevant for processTask tests
        // (only used by stop()), so any int works.
        writer = new DatabaseWriter(queue, batchRepository, fileEntryRepository, 5000);
    }

    @Test
    void processTask_marksCompleted_whenInsertedCountMatchesEntries() {
        UUID batchId = UUID.randomUUID();
        List<FileEntry> entries = List.of(
                new FileEntry(batchId, "a.txt", 10),
                new FileEntry(batchId, "b.txt", 20),
                new FileEntry(batchId, "c.txt", 30)
        );
        WriteTask task = new WriteTask(batchId, entries);

        when(fileEntryRepository.insertAll(eq(batchId), any())).thenReturn(3);

        writer.processTask(task);

        verify(batchRepository).markProcessing(batchId);
        verify(fileEntryRepository).insertAll(eq(batchId), any());
        verify(batchRepository).markCompleted(eq(batchId), eq(3), any());
        verify(batchRepository, never()).markFailed(any(), any(), any());
    }

    @Test
    void processTask_marksFailed_whenInsertedCountDiffersFromEntries() {
        UUID batchId = UUID.randomUUID();
        List<FileEntry> entries = List.of(
                new FileEntry(batchId, "a.txt", 10),
                new FileEntry(batchId, "b.txt", 20),
                new FileEntry(batchId, "c.txt", 30)
        );
        WriteTask task = new WriteTask(batchId, entries);

        // Repository reports only 2 inserts for 3 entries — integrity check must fail.
        when(fileEntryRepository.insertAll(eq(batchId), any())).thenReturn(2);

        writer.processTask(task);

        verify(batchRepository).markProcessing(batchId);
        verify(fileEntryRepository).insertAll(eq(batchId), any());
        verify(batchRepository).markFailed(eq(batchId), any(String.class), any());
        verify(batchRepository, never()).markCompleted(any(), anyInt(), any());
    }
}