<div align="center">

# Grindstone

**Multi-threaded infrastructure stress testing engine**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Hibernate](https://img.shields.io/badge/Hibernate-7-59666C?logo=hibernate&logoColor=white)](https://hibernate.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Heroku](https://img.shields.io/badge/Heroku-Deployed-430098?logo=heroku&logoColor=white)](https://heroku.com/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Grinds CPU, RAM, and dual PostgreSQL databases simultaneously with configurable multi-threaded workers.\
Real-time dashboard. Persistent writes. No deletes. Databases grow until you pull the plug.

---

![Dashboard Preview](data/image.png)

</div>

---

## How It Works

Each worker thread executes a relentless loop:

```
READ chunk from video file
    |
HASH with SHA-256 (N rounds)
    |
WRITE to PostgreSQL 1 ---+--- WRITE to PostgreSQL 2
    |                          |
READ BACK + verify hash   READ BACK + verify hash
    |
MATRIX MULTIPLY (CPU burn)
    |
ALLOCATE memory buffer (RAM pressure)
    |
REPEAT
```

All writes persist. Both databases accumulate data independently. Hash verification catches integrity failures in real time.

---

## Live Dashboard

The built-in dashboard at `/` streams metrics over WebSocket at 1-second intervals.

| Panel | Metrics |
|:---|:---|
| **System** | CPU (system + process), heap used / max, non-heap |
| **PostgreSQL** | Rows, DB size, writes/s, reads/s |
| **PostgreSQL 2** | Rows, DB size, writes/s, reads/s |
| **Throughput** | MB/s processed, total bytes |
| **DB Size Growth** | Side-by-side PG vs PG2 size over time |
| **Controls** | Start/Stop, thread count, chunk size |

---

## Tech Stack

| Layer | Technology |
|:---|:---|
| Runtime | `Java 21` / `OpenJDK` |
| Framework | `Spring Boot 4.0.6` / `Hibernate ORM 7` |
| Primary DB | `PostgreSQL` via SchemaToGo |
| Secondary DB | `PostgreSQL` via SchemaToGo |
| Connection Pool | `HikariCP` (dual pools) |
| Real-time | `WebSocket` (1s push interval) |
| Frontend | `TailwindCSS` / `Chart.js 4` |
| Deployment | `Heroku` (multi-dyno) |

---

## Configuration

<details>
<summary><b>Environment Variables</b></summary>

<br>

#### Load Test

| Variable | Default | Description |
|:---|:---:|:---|
| `LOADTEST_THREADS` | `100` | Worker threads per dyno |
| `LOADTEST_CHUNK_MB` | `10` | Chunk size read from video file |
| `LOADTEST_HASH_ROUNDS` | `5` | SHA-256 rounds per chunk |
| `LOADTEST_MATRIX_SIZE` | `200` | N x N matrix multiply |
| `LOADTEST_MEMORY_MB` | `500` | Rolling memory buffer pool (MB) |
| `LOADTEST_AUTO_START` | `false` | Begin grinding on boot |
| `LOADTEST_GENERATE` | `false` | Generate synthetic video if missing |
| `LOADTEST_GENERATE_SIZE_MB` | `100` | Generated file size |

#### PostgreSQL (Primary)

| Variable | Default | Description |
|:---|:---:|:---|
| `PG_URL` | localhost | JDBC connection string |
| `PG_USERNAME` | postgres | Database user |
| `PG_PASSWORD` | postgres | Database password |
| `HIKARI_MAX_POOL` | `4` | PG connection pool size |
| `PG_MAX_BYTES` | 3TiB | Storage capacity (bytes) |

#### PostgreSQL 2 (Secondary)

| Variable | Default | Description |
|:---|:---:|:---|
| `PG2_ENABLED` | `false` | Activate dual-database mode |
| `PG2_URL` | -- | JDBC connection string |
| `PG2_USERNAME` | -- | Database user |
| `PG2_PASSWORD` | -- | Database password |
| `PG2_HIKARI_MAX_POOL` | `4` | PG2 connection pool size |
| `PG2_MAX_BYTES` | 3TiB | Storage capacity (bytes) |

</details>

---

## Deployment

```bash
# Create app + databases
heroku create grindstone-app
heroku addons:create schematogo:premium-10
heroku addons:create schematogo:premium-10

# Configure per-dyno resources (scale as needed)
heroku config:set \
  LOADTEST_THREADS=4 \
  LOADTEST_CHUNK_MB=1 \
  LOADTEST_MEMORY_MB=10 \
  HIKARI_MAX_POOL=4 \
  LOADTEST_AUTO_START=true \
  LOADTEST_GENERATE=true \
  JAVA_OPTS="-Xmx300m -Xms200m" \
  DDL_AUTO=update \
  PG2_ENABLED=true

# Set PG credentials (from SchemaToGo addon config)
heroku config:set \
  PG_URL="jdbc:postgresql://HOST:PORT/DB" \
  PG_USERNAME=user \
  PG_PASSWORD=pass

# Set PG2 credentials (from second SchemaToGo addon)
heroku config:set \
  PG2_URL="jdbc:postgresql://HOST:PORT/DB" \
  PG2_USERNAME=user \
  PG2_PASSWORD=pass

# Deploy and scale
git push heroku main
heroku ps:scale web=10
```

> **Note:** When running multiple dynos, keep per-dyno pool sizes low.\
> Total connections = `dynos * pool_size` must stay under the database's max connections.

---

## Project Structure

```
src/main/java/com/test/load/
|
+-- config/
|   +-- PostgresDataSourceConfig    Primary datasource + EntityManager
|   +-- Pg2DataSourceConfig         Secondary datasource (conditional)
|   +-- WebSocketConfig             /ws/stats endpoint
|   +-- FileInitializer             Synthetic video generator
|
+-- entity/
|   +-- pg/PgVideoChunk             PostgreSQL entity (bytea)
|   +-- pg2/Pg2VideoChunk           PostgreSQL 2 entity (bytea)
|
+-- repository/
|   +-- pg/PgVideoChunkRepository
|   +-- pg2/Pg2VideoChunkRepository
|
+-- service/
|   +-- LoadTestService             Worker pool, dual-write loop
|   +-- StatsService                Atomic counters, snapshots
|
+-- controller/
|   +-- LoadTestController          REST endpoints
|
+-- websocket/
    +-- StatsWebSocketHandler       Broadcast to connected clients

src/main/resources/
+-- static/index.html               Dashboard (Tailwind + Chart.js)
+-- application.properties          Config with env var fallbacks
```

---

## API Reference

```
POST /api/start        Start the grind
                       Body: {"threads": 4, "chunkSizeMb": 1}

POST /api/stop         Stop all workers

GET  /api/status       Current metrics snapshot (JSON)

GET  /api/config       Running configuration

WS   /ws/stats         Real-time metrics stream (1s interval)
```

<details>
<summary><b>Example /api/status response</b></summary>

```json
{
  "cpuUsage": 34.2,
  "processCpuUsage": 10.0,
  "heapUsed": 86781776,
  "heapMax": 314572800,
  "opsPerSecond": 8.3,
  "pgRowCount": 45570,
  "pgSizeBytes": 223479092915,
  "pgInsertsPerSecond": 4.2,
  "pgReadsPerSecond": 4.2,
  "pg2Enabled": true,
  "pg2RowCount": 21217,
  "pg2SizeBytes": 21454405632,
  "pg2InsertsPerSecond": 4.1,
  "pg2ReadsPerSecond": 4.1,
  "activeThreads": 4,
  "errors": 0,
  "running": true
}
```

</details>

---

<div align="center">
<sub>Built to break things responsibly.</sub>
</div>
