package com.github.cr0wsec.ziprace.repository;

import com.github.cr0wsec.ziprace.model.BatchStatus;
import com.github.cr0wsec.ziprace.model.UploadBatch;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence operations for {@link UploadBatch} state. State transitions
 * are exposed as separate methods to prevent illegal status updates.
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public BatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists a new batch in PENDING state. Only id, total_files, and created_at
     * from the parameter are written. Other fields use database defaults.
     */
    public void saveAsPending(UploadBatch batch) {
        jdbcTemplate.update(
                "INSERT INTO upload_batch (id, total_files, created_at) VALUES (?, ?, ?)",
                batch.id().toString(), batch.totalFiles(), batch.createdAt().toEpochMilli()
        );
    }

    public void markCompleted(UUID id, int processedFiles, Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE upload_batch SET status = ?, processed_files = ?, completed_at = ? WHERE id = ?",
                BatchStatus.COMPLETED.name(),
                processedFiles,
                completedAt.toEpochMilli(),
                id.toString()
        );
    }

    public void markFailed(UUID id, String errorMessage, Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE upload_batch SET status = ?, error_message = ?, completed_at = ? WHERE id = ?",
                BatchStatus.FAILED.name(),
                errorMessage,
                completedAt.toEpochMilli(),
                id.toString()
        );
    }

    public void markProcessing(UUID id) {
        jdbcTemplate.update(
                "UPDATE upload_batch SET status = ? WHERE id = ?",
                BatchStatus.PROCESSING.name(),
                id.toString()
        );
    }

    /**
     * Retrieves a batch by its UUID.
     *
     * @param id batch identifier
     * @return the batch if found, or {@code Optional.empty()} if no row matches.
     *         Never returns {@code null}.
     */
    public Optional<UploadBatch> findById(UUID id) {
        try {
            UploadBatch batch = jdbcTemplate.queryForObject(
                    "SELECT id, status, total_files, processed_files, error_message, created_at, completed_at FROM upload_batch WHERE id = ?",
                    (rs, rowNum) -> {
                        Long completedMs = rs.getObject("completed_at", Long.class);
                        Instant completedAt = (completedMs == null) ? null : Instant.ofEpochMilli(completedMs);

                        return new UploadBatch(
                            UUID.fromString(rs.getString("id")),
                            BatchStatus.valueOf(rs.getString("status")),
                            rs.getInt("total_files"),
                            rs.getInt("processed_files"),
                            rs.getString("error_message"),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            completedAt
                        );
                    },
                    id.toString()
            );
            return Optional.of(batch);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}


