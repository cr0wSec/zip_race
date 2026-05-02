package com.github.cr0wsec.ziprace.repository;

import com.github.cr0wsec.ziprace.model.FileEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class FileEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    public FileEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Bulk-inserts file entries for a given batch using JDBC batch updates.
     * <p>
     * Returns the number of statements executed (= rows attempted), not the sum
     * of affected rows. For simple INSERTs where each statement affects 1 row,
     * the caller can compare this value to {@code entries.size()} to verify
     * integrity.
     *
     * @param batchId UUID of the parent batch
     * @param entries file entries to insert
     * @return number of statements executed
     */
    public int insertAll(UUID batchId, List<FileEntry> entries) {
        String insertQuery = "INSERT INTO file_entry(batch_id, filename, file_size) VALUES (?,?,?)";

        List<Object[]> batchArgs = entries.stream().map(
                e -> new Object[]{
                        batchId.toString(),
                        e.fileName(),
                        e.fileSize()
                }
        ).toList();

        int[] result = jdbcTemplate.batchUpdate(insertQuery, batchArgs);

        return result.length;
    }

}
