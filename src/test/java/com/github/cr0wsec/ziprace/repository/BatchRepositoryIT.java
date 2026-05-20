package com.github.cr0wsec.ziprace.repository;

import com.github.cr0wsec.ziprace.model.BatchStatus;
import com.github.cr0wsec.ziprace.model.UploadBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration tests for {@link BatchRepository}.
 * <p>
 * Validates the SQL strings against a real SQLite database (in-memory). Mockito
 * unit tests cannot catch typos in SQL or column-name mismatches — these tests
 * close that gap.
 * <p>
 * Each state transition has its own test to make breakage location obvious.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import(BatchRepository.class)
class BatchRepositoryIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BatchRepository batchRepository;

    @Test
    void saveAsPending_persistsBatchInPendingState() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        UploadBatch batch = new UploadBatch(id, BatchStatus.PENDING, 5, 0, null, now, null);

        batchRepository.saveAsPending(batch);

        // Verify status defaults to PENDING (from schema CHECK) and total_files matches.
        // created_at is exercised by findById_returnsPresent which round-trips the timestamp.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM upload_batch WHERE id = ?",
                String.class, id.toString()
        );
        Integer totalFiles = jdbcTemplate.queryForObject(
                "SELECT total_files FROM upload_batch WHERE id = ?",
                Integer.class, id.toString()
        );

        assertThat(status).isEqualTo("PENDING");
        assertThat(totalFiles).isEqualTo(5);
    }

    @Test
    void findById_returnsPresent_whenBatchExists() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        UploadBatch original = new UploadBatch(id, BatchStatus.PENDING, 3, 0, null, now, null);
        batchRepository.saveAsPending(original);

        Optional<UploadBatch> found = batchRepository.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().status()).isEqualTo(BatchStatus.PENDING);
        assertThat(found.get().totalFiles()).isEqualTo(3);
        assertThat(found.get().processedFiles()).isEqualTo(0);
        assertThat(found.get().errorMessage()).isNull();
        assertThat(found.get().completedAt()).isNull();
        // createdAt is stored as epoch ms, so we compare millisecond precision
        assertThat(found.get().createdAt().toEpochMilli()).isEqualTo(now.toEpochMilli());
    }

    @Test
    void findById_returnsEmpty_whenBatchDoesNotExist() {
        UUID unknownId = UUID.randomUUID();

        Optional<UploadBatch> found = batchRepository.findById(unknownId);

        assertThat(found).isEmpty();
    }

    @Test
    void markProcessing_transitionsStatusToProcessing() {
        UUID id = UUID.randomUUID();
        batchRepository.saveAsPending(new UploadBatch(id, BatchStatus.PENDING, 2, 0, null, Instant.now(), null));

        batchRepository.markProcessing(id);

        Optional<UploadBatch> found = batchRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(BatchStatus.PROCESSING);
    }

    @Test
    void markCompleted_setsStatusProcessedFilesAndCompletedAt() {
        UUID id = UUID.randomUUID();
        batchRepository.saveAsPending(new UploadBatch(id, BatchStatus.PENDING, 4, 0, null, Instant.now(), null));
        Instant completedAt = Instant.now();

        batchRepository.markCompleted(id, 4, completedAt);

        Optional<UploadBatch> found = batchRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(found.get().processedFiles()).isEqualTo(4);
        assertThat(found.get().completedAt()).isNotNull();
        assertThat(found.get().completedAt().toEpochMilli()).isEqualTo(completedAt.toEpochMilli());
    }

    @Test
    void markFailed_setsStatusErrorMessageAndCompletedAt() {
        UUID id = UUID.randomUUID();
        batchRepository.saveAsPending(new UploadBatch(id, BatchStatus.PENDING, 7, 0, null, Instant.now(), null));
        Instant failedAt = Instant.now();
        String errorMessage = "Queue full, request rejected";

        batchRepository.markFailed(id, errorMessage, failedAt);

        Optional<UploadBatch> found = batchRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(BatchStatus.FAILED);
        assertThat(found.get().errorMessage()).isEqualTo(errorMessage);
        assertThat(found.get().completedAt()).isNotNull();
        assertThat(found.get().completedAt().toEpochMilli()).isEqualTo(failedAt.toEpochMilli());
    }
}