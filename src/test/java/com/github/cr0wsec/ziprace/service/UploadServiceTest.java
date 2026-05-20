package com.github.cr0wsec.ziprace.service;

import com.github.cr0wsec.ziprace.concurrent.WriteTask;
import com.github.cr0wsec.ziprace.exception.EmptyZipException;
import com.github.cr0wsec.ziprace.exception.InvalidZipEntryException;
import com.github.cr0wsec.ziprace.exception.QueueFullException;
import com.github.cr0wsec.ziprace.model.UploadBatch;
import com.github.cr0wsec.ziprace.repository.BatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UploadService#handleUpload(InputStream)}.
 * <p>
 * Covers the four distinct paths the controller can observe:
 * <ul>
 *     <li>Nominal upload: UUID returned, batch persisted PENDING, task queued.</li>
 *     <li>Queue full: batch marked FAILED, {@link QueueFullException} thrown.</li>
 *     <li>Empty zip: {@link EmptyZipException} thrown, no DB writes, no queue offer.</li>
 *     <li>Suspicious filename ({@code ..}): {@link InvalidZipEntryException} thrown.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    @Mock
    BatchRepository batchRepository;

    @Mock
    BlockingQueue<WriteTask> writeQueue;

    UploadService uploadService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        uploadService = new UploadService(batchRepository, writeQueue, 1000);
    }

    @Test
    void handleUpload_nominal_returnsBatchIdAndQueuesTask() throws IOException {
        when(writeQueue.offer(any(WriteTask.class))).thenReturn(true);

        InputStream zip = createZipWithEntries(3);
        UUID batchId = uploadService.handleUpload(zip);

        assertThat(batchId).isNotNull();
        verify(batchRepository).saveAsPending(any(UploadBatch.class));
        verify(writeQueue).offer(any(WriteTask.class));
        verify(batchRepository, never()).markFailed(any(), anyString(), any());
    }

    @Test
    void handleUpload_queueFull_marksFailedAndThrows() throws IOException {
        when(writeQueue.offer(any(WriteTask.class))).thenReturn(false);

        InputStream zip = createZipWithEntries(3);

        assertThatThrownBy(() -> uploadService.handleUpload(zip))
                .isInstanceOf(QueueFullException.class);

        verify(batchRepository).saveAsPending(any(UploadBatch.class));
        verify(batchRepository).markFailed(any(UUID.class), anyString(), any());
    }

    @Test
    void handleUpload_emptyZip_throwsEmptyZipException() throws IOException {
        InputStream zip = createEmptyZip();

        assertThatThrownBy(() -> uploadService.handleUpload(zip))
                .isInstanceOf(EmptyZipException.class);

        // No batch persisted, no task queued - the empty-zip check happens before persistence.
        verifyNoInteractions(batchRepository);
        verifyNoInteractions(writeQueue);
    }

    @Test
    void handleUpload_filenameWithDotDot_throwsInvalidZipEntryException() throws IOException {
        InputStream zip = createZipWithFilename("../etc/passwd");

        assertThatThrownBy(() -> uploadService.handleUpload(zip))
                .isInstanceOf(InvalidZipEntryException.class);

        verifyNoInteractions(batchRepository);
        verifyNoInteractions(writeQueue);
    }

    // helpers

    /**
     * Builds an in-memory zip containing N entries with non-empty content,
     * so that the streaming size check ({@code transferTo}) returns > 0.
     */
    private static InputStream createZipWithEntries(int count) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < count; i++) {
                ZipEntry e = new ZipEntry("file" + i + ".txt");
                zos.putNextEntry(e);
                zos.write(("content " + i).getBytes());
                zos.closeEntry();
            }
        }
        return new ByteArrayInputStream(baos.toByteArray());
    }

    /**
     * Builds a structurally valid zip with no entries (empty central directory).
     * Used to exercise the {@link EmptyZipException} path.
     */
    private static InputStream createEmptyZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // open + close = central directory written with zero entries.
        }
        return new ByteArrayInputStream(baos.toByteArray());
    }

    /**
     * Builds a zip with a single entry of the given filename. Used to exercise
     * the path-traversal rejection branch.
     */
    @SuppressWarnings("resource")
    private static InputStream createZipWithFilename(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry e = new ZipEntry(filename);
            zos.putNextEntry(e);
            zos.write("payload".getBytes());
            zos.closeEntry();
        }
        return new ByteArrayInputStream(baos.toByteArray());
    }
}