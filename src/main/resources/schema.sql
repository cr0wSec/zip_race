CREATE TABLE IF NOT EXISTS upload_batch (
                              id              TEXT PRIMARY KEY,
                              status          TEXT NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
                              total_files     INTEGER NOT NULL DEFAULT 0,
                              processed_files INTEGER NOT NULL DEFAULT 0,
                              error_message   TEXT,
                              created_at      INTEGER NOT NULL,
                              completed_at    INTEGER
);

CREATE TABLE IF NOT EXISTS file_entry (
                            id        INTEGER PRIMARY KEY AUTOINCREMENT,
                            batch_id  TEXT NOT NULL REFERENCES upload_batch(id),
                            filename  TEXT NOT NULL,
                            file_size INTEGER NOT NULL
);
