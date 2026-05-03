# zip-race

> A Spring Boot service that ingests thousands of concurrent zip uploads into an embedded SQLite database without losing data, demonstrating how to design controlled concurrency on a single-writer storage engine.

---
## Context

Some embedded systems routinely handle thousands of concurrent events that must be persisted reliably to a local database. SQLite is the natural fit for this context: zero server, single-file storage, minimal footprint. However, SQLite has one defining constraint: only one writer can write to the database at a time.

Naive concurrent designs against SQLite fail in two ways:

1. Multiple writer threads contend for the database lock, producing `SQLITE_BUSY` errors and unpredictable retries.
2. Unbounded queues of pending writes exhaust memory under spikes, taking the entire process down.

I wanted to find a way to absorb a burst of writes safely against a serializing backend. The goal is not to write fast.

I implemented the **Single Writer pattern**: parallel HTTP threads parse uploads and push immutable write tasks into a bounded queue. At the same time, a dedicated writer thread drains the queue (one taks at a time) and writes to SQLite. Backpressure (signaling producers to calm down when the system is overwhelmed) is enforced via HTTP 503 errors when the queue saturates, ensuring the system degrades predictably instead of cascading into failure.

---

## Non-Goals

- **Authentication, authorization, TLS** — explicitly out of scope; this is a concurrency demo, not a production API.
- **Horizontal scaling / multi-instance deployment** — incompatible with embedded SQLite by design.
- **Storing file content** in the database — Storing the file content alongside the metadata: out of scope. The use case targets metadata indexing (filename + size) for inventory/audit purposes, not content retention. Content storage would be appropriate for small blobs (< 10 KB per file, where SQLite is actually faster than filesystem [^1]), but the workload here can include arbitrarily-sized files. For large content, the standard pattern is metadata in the database + content in object storage (S3-compatible) or filesystem." [^1]: [SQLite — 35% Faster Than The Filesystem](https://www.sqlite.org/fasterthanfs.html)
- **Web UI / dashboard** — observability is exposed through structured logs and (planned) Prometheus metrics, not a UI.
- **Production hardening** (retry policies, circuit breakers, distributed tracing, rate limiting per client).

---

## Requirements

### Functional

|Priority|Requirement|
|---|---|
|MUST|Accept zip uploads via `POST /uploads` and return a `batchId` immediately|
|MUST|Persist file metadata (filename, size) for every entry of every accepted zip|
|MUST|Reject excess load with HTTP 503 when the internal queue is saturated|
|MUST|Mark each batch with a final status: `COMPLETED` or `FAILED`|
|MUST|Allow status retrieval via `GET /batches/{id}`|
|MUST|Validate input: Content-Type, body size, entry count, filename safety|
|SHOULD|Verify integrity by comparing announced vs. actually inserted file count|
|MAY|Expose runtime metrics for observability|

### Non-Functional

Targets validated in two environments: macOS native (Apple Silicon M4 Pro, peak hardware performance) and Docker container-to-container (reproducible reference, closer to a Linux production target). This POC is hardware-dependent, figures will differ in other environments.

**Docker container-to-container — reference environment for design validation:**

| Dimension                                         | Target                                              | Measured (Docker, accept-count=1024)      |
| ------------------------------------------------- | --------------------------------------------------- | ----------------------------------------- |
| Latency (1 000 concurrent clients, 500-file zips) | < 1 500 ms P99                                      | **510 ms P99**, 0 KO                      |
| Backpressure at 5 000 clients                     | 100 % of rejections via HTTP 503, 0 TCP-layer drops | Confirmed (3 922 / 3 922 KO are HTTP 503) |
| Standard deviation under load                     | Predictable (< 200 ms at nominal load)              | 95 ms at 1 000 clients                    |

**macOS native — peak hardware reference (not reproducible cross-session):**

|Dimension|Measured|
|---|---|
|Latency (1 000 concurrent clients, 500-file zips)|226 ms P99|
|Throughput (peak, under saturation)|~519 k INSERTs/s|

The macOS native environment shows the peak performance achievable on this hardware in good conditions, but those numbers were not reproducible across sessions (variance up to ~3× because of background macOS activity). The Docker container-to-container numbers are reproducible, free of TCP-layer artifacts (after tuning Tomcat `accept-count` to 1024, it was originally set to 100, generating TCP drops), and closer to what would be a production environment. The Docker run is the **reference for design validation**, the macOS native run is informational.

See [TEST.md]() for the full benchmark methodology.

### Hard Constraints

- **Database:** SQLite, imposed by the embedded context.
- **JVM:** Java 25 LTS (current LTS, supported until 2033; Java 21 loses permissive licensing in September 2026).
- **Single-process / single-node** deployment. No external message broker, no clustering.

---

## Architecture

```
N concurrent clients (HTTP POST /uploads)
           │
           ▼
   Tomcat worker threads (platform threads)
   • parse zip stream
   • validate entries (size, filename, count)
   • save batch as PENDING
   • offer WriteTask to queue
           │
           ▼ offer() — non-blocking, rejects if full
   Bounded BlockingQueue (ArrayBlockingQueue, capacity 1000)
           │
           ▼ take() — blocks until task available
   DatabaseWriter (single dedicated thread)
   • one transaction per task
   • batched JDBC INSERT
   • mark batch COMPLETED or FAILED
           │
           ▼
   SQLite (WAL mode, single writer connection)
```

Read endpoints (`GET /batches/{id}`) currently share the same single-connection Hikari pool, so reads serialize against writes at the JDBC layer. WAL mode permits concurrent readers/writer at the SQLite layer, but the pool-size-1 sequencing happens earlier in the stack, meaning the WAL benefit is therefore only partially exploited today. A dedicated read-only pool would need to be implemented to benefit from WAL (see _Known Limitations_ and the configuration table).

This pattern is inspired by [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/disruptor.html): producers push, a single consumer drains.

### Why a single writer thread?

**SQLite physically allows only one writer.** Even with N writer threads, only one would actually write at a time. The others would either block on the lock or receive `SQLITE_BUSY` errors. Adding more threads here would only increase complexity, not throughput.

**Parallelism happens upstream of the queue, not downstream.** Each request thread parses its zip independently and inserts a task in the queue. The serialization point (queue → writer) is necessary because of SQLite's nature.

This would probably not be needed if PostgreSQL or another concurrent-write database was allowed. There would be a pool of N writers and a connection pool.
### Layered code structure

```
com.github.cr0wsec.ziprace
├── controller/              HTTP layer (REST endpoints)
│   ├── UploadController
│   └── BatchController
├── service/                 Business logic
│   ├── UploadService
│   └── BatchService
├── repository/              SQL interactions with database
│   ├── BatchRepository
│   └── FileEntryRepository
├── concurrent/              Concurrency infrastructure
│   ├── WriteTask            (record, immutable command)
│   ├── WriteQueueConfig     (exposes the BlockingQueue bean)
│   └── DatabaseWriter       (single writer thread)
├── model/                   Domain records
│   ├── UploadBatch
│   ├── FileEntry
│   └── BatchStatus          (enum, prevents illegal states)
└── exception/
    ├── EmptyZipException
    ├── InvalidZipEntryException
    ├── QueueFullException
    ├── TooManyZipEntriesException
    ├── ZipProcessingException
    └── handler/
        └── GlobalExceptionHandler
```

Strict layer rules: controllers call services, services call repositories. Controllers never touch repositories directly.

---

## Getting Started

### Prerequisites

```
- Java 25 LTS
- Maven 3.9+ (the wrapper is included; Maven binary not strictly required)
- SQLite client (optional, for inspecting the database)
- 200 MB free disk space for ./data
```

### Installation

```bash
# Clone
git clone https://github.com/cr0wsec/zip-race.git
cd zip-race

# The Maven wrapper handles dependencies — no manual install step
mkdir -p data    # SQLite file location

# Run
./mvnw spring-boot:run
```

### Running with Docker

```bash
# Build both images (app + gatling load generator)
docker compose build app
docker compose --profile bench build gatling

# Start the app on port 8080 with a persistent volume for SQLite
docker compose up -d app
docker compose ps  # wait for "(healthy)"
```

See [Dockerfile](), [Dockerfile.gatling](), and [docker-compose.yml]() for details. The Docker setup is the **reference environment** for reproducible benchmarks — see [TEST.md]().

### Verify It Works

```bash
# Create a small test zip
mkdir -p /tmp/zip-race-test && cd /tmp/zip-race-test
for i in $(seq 1 10); do echo "content $i" > "file$i.txt"; done
zip test.zip file*.txt

# Upload
curl -X POST http://localhost:8080/uploads \
     -H "Content-Type: application/zip" \
     --data-binary @test.zip

# Expected response (batchId will differ)
# {"batchId":"c95bcb85-9862-4ad8-9ece-9f0ab405b6c6"}

# Retrieve batch status
curl http://localhost:8080/batches/<batchId>
```

### Running the Load Test

**Containerized (recommended — reproducible):**

```bash
docker compose down -v       # reset SQLite volume to start clean
docker compose up -d app
docker compose ps            # wait for "(healthy)"

./run-bench.sh 1000          # 1 000 concurrent clients (default 1 000)
# Or: ./run-bench.sh 5000    # to test backpressure under saturation

open ./gatling-reports/uploadsimulation-*/index.html
```

**Native (peak hardware performance, less reproducible):**

```bash
# In one terminal: keep the app running
./mvnw spring-boot:run

# In another terminal: launch Gatling
./mvnw gatling:test

# Reports are generated in target/gatling/uploadsimulation-<timestamp>/index.html
```

See [TEST.md]() for benchmark methodology and result interpretation.

---

## Configuration

|Variable / Property|Required|Default|Description|
|---|---|---|---|
|`spring.datasource.url`|Yes|—|JDBC URL to the SQLite file. Includes PRAGMA parameters (`journal_mode=WAL&synchronous=NORMAL&cache_size=-64000&temp_store=MEMORY`)|
|`spring.datasource.driver-class-name`|Yes|—|`org.sqlite.JDBC`|
|`spring.datasource.hikari.maximum-pool-size`|No|`1`|Pool size. `1` reflects current single-writer architecture. Increase only after introducing a separate read-only pool|
|`spring.threads.virtual.enabled`|No|`false` (Spring default, not set explicitly)|Tested and rejected for this workload — see _Decision 4_. Setting `true` produced a ~2× P99 degradation. Not declared in `application.yaml` to keep the file free of redundant defaults.|
|`spring.sql.init.mode`|No|`embedded`|`always` to apply `schema.sql` on every startup|
|`ziprace.queue.capacity`|No|`1000`|Bounded queue capacity. Increase to accept more bursts at the cost of higher worst-case latency|
|`ziprace.writer.shutdown-timeout-ms`|No|`5000`|Time the JVM waits for the writer thread to drain on shutdown|
|`ziprace.upload.max-size-bytes`|No|`104857600` (100 MB)|Maximum accepted body size per upload, validated against `Content-Length`|
|`server.tomcat.accept-count`|No|`1024`|TCP backlog queue size (kernel-side). Default Tomcat is 100; raised to 1024 (NGINX/Apache parity, under Linux `somaxconn=4096`) after benchmarks showed default caused TCP-layer drops at burst loads. See [TEST.md](https://claude.ai/chat/TEST.md) — _Containerized Benchmarks_.|

> **No secrets are stored in this repository.** This service has no authentication, no third-party integrations, and no credentials by design.
