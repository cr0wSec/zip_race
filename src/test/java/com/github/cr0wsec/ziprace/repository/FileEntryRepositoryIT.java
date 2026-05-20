package com.github.cr0wsec.ziprace.repository;

import com.github.cr0wsec.ziprace.model.FileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration tests for {@link FileEntryRepository}.
 * <p>
 * Uses {@link JdbcTest} for a sliced Spring context — only DataSource and
 * JdbcTemplate are loaded, no controllers or services. {@code Replace.NONE}
 * keeps our SQLite DataSource (otherwise Spring would substitute H2).
 * <p>
 * The {@code test} profile points to {@code jdbc:sqlite::memory:}, so each
 * test class starts with an empty DB and leaves nothing behind.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import(FileEntryRepository.class)
class FileEntryRepositoryIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    FileEntryRepository fileEntryRepository;

    private UUID batchId;

    @BeforeEach
    void seedParentBatch() {
        // file_entry has a foreign key to upload_batch — we must seed a parent row
        // before inserting children. UUID is per-test for isolation.
        batchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO upload_batch (id, total_files, created_at) VALUES (?, ?, ?)",
                batchId.toString(), 0, Instant.now().toEpochMilli()
        );
    }

    @Test
    void insertAll_persistsAllRowsAndReturnsCount() {
        List<FileEntry> entries = List.of(
                new FileEntry(batchId, "a.txt", 10),
                new FileEntry(batchId, "b.txt", 20),
                new FileEntry(batchId, "c.txt", 30)
        );

        int returned = fileEntryRepository.insertAll(batchId, entries);

        assertThat(returned).isEqualTo(3);

        Integer rowsInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_entry WHERE batch_id = ?",
                Integer.class, batchId.toString()
        );
        assertThat(rowsInDb).isEqualTo(3);
    }

    @Test
    void insertAll_withEmptyList_returnsZeroAndInsertsNothing() {
        int returned = fileEntryRepository.insertAll(batchId, List.of());

        assertThat(returned).isEqualTo(0);

        Integer rowsInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_entry WHERE batch_id = ?",
                Integer.class, batchId.toString()
        );
        assertThat(rowsInDb).isEqualTo(0);
    }

    @Test
    void insertAll_withUnknownBatchId_failsForeignKeyConstraint() {
        UUID orphanBatchId = UUID.randomUUID();  // not seeded

        List<FileEntry> entries = List.of(
                new FileEntry(orphanBatchId, "a.txt", 10)
        );

        // SQLite enforces FK constraints because foreign_keys=ON is set in the
        // JDBC URL (applied per-connection by sqlite-jdbc). Spring's exception
        // translator doesn't have specific mappings for SQLite, so the FK
        // violation surfaces as UncategorizedSQLException (a DataAccessException
        // subclass) rather than DataIntegrityViolationException. We assert on
        // the common parent and check the message for the FK signal.
        assertThatThrownBy(() -> fileEntryRepository.insertAll(orphanBatchId, entries))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("SQLITE_CONSTRAINT_FOREIGNKEY");

        // No row should have been inserted.
        Integer rowsInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_entry WHERE batch_id = ?",
                Integer.class, orphanBatchId.toString()
        );
        assertThat(rowsInDb).isEqualTo(0);
    }
}