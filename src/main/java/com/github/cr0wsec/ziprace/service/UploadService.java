package com.github.cr0wsec.ziprace.service;

import com.github.cr0wsec.ziprace.concurrent.DatabaseWriter;
import com.github.cr0wsec.ziprace.concurrent.WriteTask;
import com.github.cr0wsec.ziprace.exception.*;
import com.github.cr0wsec.ziprace.model.BatchStatus;
import com.github.cr0wsec.ziprace.model.FileEntry;
import com.github.cr0wsec.ziprace.model.UploadBatch;
import com.github.cr0wsec.ziprace.repository.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Orchestrates the upload flow: parses the incoming zip, persists the batch
 * as PENDING, and submits the entries to the write queue for asynchronous
 * processing by {@link DatabaseWriter}.
 * <p>
 * Returns immediately once the task is queued; the actual database write
 * happens later, on the writer thread.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private final int maxEntriesPerZip;

    private final BatchRepository batchRepository;
    private final BlockingQueue<WriteTask> writeQueue;

    public UploadService(
            BatchRepository batchRepository,
            BlockingQueue<WriteTask> writeQueue,
            @Value("${ziprace.upload.max-entries-per-zip:1000}") int maxEntriesPerZip) {
        this.batchRepository = batchRepository;
        this.writeQueue = writeQueue;
        this.maxEntriesPerZip = maxEntriesPerZip;
    }


    /**
     * Accepts a zip upload, persists a PENDING batch, and enqueues the entries
     * for asynchronous database write. Returns immediately once the task is queued.
     *
     * @param zipStream raw zip bytes from the HTTP request body
     * @return the UUID of the newly created batch
     * @throws InvalidZipEntryException if any entry has an undeclared size
     * @throws EmptyZipException        if the zip contains no file entries
     * @throws ZipProcessingException   if the stream is not a valid zip
     * @throws QueueFullException       if the write queue is saturated
     */
    public UUID handleUpload(InputStream zipStream) {

        UUID batchId = UUID.randomUUID();
        log.info("Received upload request, batchId={}", batchId);

        List<FileEntry> entries = parseZipEntries(zipStream, batchId);

        UploadBatch batch = new UploadBatch(batchId, BatchStatus.PENDING, entries.size(), 0, null, Instant.now(), null);
        batchRepository.saveAsPending(batch);

        enqueueOrReject(batchId, entries);
        return batchId;
    }

    /**
     * Parses a zip stream and extracts file metadata for each non-directory entry.
     * <p>
     * Directory entries are silently skipped. Entries with an undeclared size
     * (header reports -1) are treated as malformed and reject the whole upload.
     * <p>
     * The method also rejects empty zips, since accepting them would create
     * batches with zero files — a state that has no business meaning here.
     *
     * @throws InvalidZipEntryException if any entry has an undeclared size
     * @throws EmptyZipException        if the zip contains no file entries
     * @throws ZipProcessingException   if the stream cannot be read as a valid zip
     */
    private List<FileEntry> parseZipEntries(InputStream zipStream, UUID batchId) {

        List<FileEntry> entries = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) !=  null) {

                if (entry.isDirectory()) {
                    continue;
                }

                if (entries.size() >= maxEntriesPerZip) {
                    throw new TooManyZipEntriesException("Upload failed, too many entries in zip (max " + maxEntriesPerZip + ")");
                }

                if (entry.getName().contains("..")
                        || entry.getName().contains("\0")
                        || entry.getName().length() > 1024) {
                    throw new InvalidZipEntryException("Upload failed, invalid filename");
                }

                // Compute the actual size by reading the entry content
                long actualSize = zis.transferTo(OutputStream.nullOutputStream());

                entries.add(new FileEntry(batchId, entry.getName(), actualSize));
            }
        } catch  (IOException e) {
            throw new ZipProcessingException(e.getMessage(), e);
        }

        if (entries.isEmpty()) {
            throw new EmptyZipException("Upload failed, zip doesn't contain any file");
        }

        return entries;
    }

    /**
     * Submits the write task to the queue, or rejects the upload if the queue is full.
     * <p>
     * On rejection, marks the previously-saved PENDING batch as FAILED before throwing,
     * to avoid leaving orphan PENDING rows in the database.
     *
     * @throws QueueFullException if the queue cannot accept the task immediately
     */
    private void enqueueOrReject(UUID batchId, List<FileEntry> entries) {
        WriteTask task = new WriteTask(batchId, entries);
        if (!writeQueue.offer(task)) {
            batchRepository.markFailed(batchId, "Queue full, request rejected", Instant.now());
            log.warn("Upload rejected - queue full, batchId={}", batchId);
            throw new QueueFullException();
        }
    }
}