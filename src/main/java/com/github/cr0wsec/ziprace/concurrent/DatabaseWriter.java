package com.github.cr0wsec.ziprace.concurrent;

import com.github.cr0wsec.ziprace.repository.BatchRepository;
import com.github.cr0wsec.ziprace.repository.FileEntryRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;

/**
 * Single-writer thread that drains the {@link WriteTask} queue and persists
 * each task to SQLite. The cornerstone of the project's Single Writer pattern:
 * upload threads produce concurrently, this consumer serializes writes.
 * <p>
 * Lifecycle is managed by Spring: {@link #start()} runs at application startup
 * via {@code @PostConstruct}; {@link #stop()} runs at shutdown via
 * {@code @PreDestroy}. The thread itself is robust: any exception thrown while
 * processing a task is caught and logged; the thread continues to the next task.
 *
 * @see WriteTask
 */
@Component
public class DatabaseWriter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWriter.class);

    private final BlockingQueue<WriteTask> queue;
    private final BatchRepository batchRepository;
    private final FileEntryRepository fileEntryRepository;
    private final int shutdownTimeoutMs;

    private Thread writerThread;

    public DatabaseWriter(
            BlockingQueue<WriteTask> queue,
            BatchRepository batchRepository,
            FileEntryRepository fileEntryRepository,
            @Value("${ziprace.writer.shutdown-timeout-ms:5000}") int shutdownTimeoutMs
) {
        this.queue = queue;
        this.batchRepository = batchRepository;
        this.fileEntryRepository = fileEntryRepository;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }

    @PostConstruct
    public void start() {
        writerThread = new Thread(this::run, "database-writer");
        writerThread.start();
        log.info("DatabaseWriter started");
    }

    /**
     * Initiates a graceful shutdown:
     * <ol>
     *   <li>{@code interrupt()} wakes any blocking {@code queue.take()} so the
     *       writer can observe the shutdown signal immediately.</li>
     *   <li>{@code join(timeout)} gives the writer up to
     *       {@code ziprace.writer.shutdown-timeout-ms} to finish its current task
     *       and exit cleanly.</li>
     * </ol>
     * Any task still in the queue at this point is lost — the queue is in-memory
     * by design (see {@code Known Limitations} in the README).
     */
    @PreDestroy
    public void stop() throws InterruptedException {
        log.info("DatabaseWriter stopping...");
        writerThread.interrupt();
        writerThread.join(shutdownTimeoutMs);
        log.info("DatabaseWriter stopped");
    }


    /**
     * Main loop. Blocks on {@code queue.take()} until a task is available, then
     * delegates to {@link #processTask}. Two distinct exception paths:
     * <ul>
     *   <li>{@link InterruptedException}: triggered by {@link #stop()}. The flag
     *       is restored via {@code Thread.currentThread().interrupt()} (the JVM
     *       clears it when the exception is thrown), then the loop exits.</li>
     *   <li>Any other {@link Exception}: logged but does not stop the writer.
     *       A buggy task must not bring down the entire pipeline.</li>
     * </ul>
     */
    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            WriteTask task = null;
            try {
                task = queue.take();
                processTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to process task {}", task != null ? task.batchId() : "unknown", e);
            }
        }
    }

    /**
     * Persists a single task in three steps: mark batch as PROCESSING, bulk-insert
     * file entries, mark batch as COMPLETED or FAILED based on the inserted-row
     * count vs. expected.
     * <p>
     * <strong>Note:</strong> these three operations are not wrapped in a single
     * transaction. If the JVM crashes between {@code insertAll} and the final mark,
     * the batch row stays in {@code PROCESSING} forever — see
     * {@code Known Limitations} in the README. The trade-off is intentional for
     * this POC; production would wrap the body in {@code @Transactional}.
     * <p>
     * Visibility is package-private rather than private to allow direct
     * unit testing without reflection. Not part of the public contract.
     * @param task the upload task to persist
     */
     void processTask(WriteTask task) {
        batchRepository.markProcessing(task.batchId());
        int inserted = fileEntryRepository.insertAll(task.batchId(), task.entries());
        if (inserted == task.entries().size()) {
            batchRepository.markCompleted(task.batchId(), inserted, Instant.now());
        } else {
            log.warn("Count mismatch for batch {}: expected {}, got {}",
                    task.batchId(), task.entries().size(), inserted);
            batchRepository.markFailed(task.batchId(), String.format("Count mismatch: expected %d, got %d", task.entries().size(), inserted), Instant.now());
        }
    }
}