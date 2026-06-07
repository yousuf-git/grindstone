<div align="center">

# Grindstone

**Multi-threaded infrastructure stress testing engine**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Hibernate](https://img.shields.io/badge/Hibernate-7-59666C?logo=hibernate&logoColor=white)](https://hibernate.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Heroku](https://img.shields.io/badge/Heroku-Deployed-430098?logo=heroku&logoColor=white)](https://heroku.com/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Grinds CPU, RAM, and dual databases simultaneously with configurable multi-threaded workers.\
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
WRITE to PostgreSQL ----+---- WRITE to MySQL
    |                         |
READ BACK + verify hash  READ BACK + verify hash
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
| **MySQL** | Rows, DB size, writes/s, reads/s |
| **Throughput** | MB/s processed, total bytes |
| **DB Size Growth** | Side-by-side PG vs MySQL size over time |
| **Controls** | Start/Stop, thread count, chunk size |

---

## Tech Stack

| Layer | Technology |
|:---|:---|
| Runtime | `Java 21` / `OpenJDK` |
| Framework | `Spring Boot 4.0.6` / `Hibernate ORM 7` |
| Primary DB | `PostgreSQL 17` via Heroku Postgres |
| Secondary DB | `MySQL` via JawsDB Maria |
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

#### PostgreSQL

| Variable | Default | Description |
|:---|:---:|:---|
| `JDBC_DATABASE_URL` | localhost | Set automatically by Heroku |
| `HIKARI_MAX_POOL` | `120` | PG connection pool size |

#### MySQL (Secondary)

| Variable | Default | Description |
|:---|:---:|:---|
| `MYSQL_ENABLED` | `false` | Activate dual-database mode |
| `MYSQL_URL` | -- | JDBC connection string |
| `MYSQL_USERNAME` | -- | Database user |
| `MYSQL_PASSWORD` | -- | Database password |
| `MYSQL_HIKARI_MAX_POOL` | `4` | MySQL connection pool size |

</details>

---

## Deployment

```bash
# Create app + databases
heroku create grindstone-app
heroku addons:create heroku-postgresql:standard-2
heroku addons:create jawsdb-maria:extended

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
  MYSQL_ENABLED=true

# Set MySQL credentials (parse from JAWSDB_MARIA_URL)
heroku config:set \
  MYSQL_URL="jdbc:mysql://HOST:PORT/DB?useSSL=true&serverTimezone=UTC" \
  MYSQL_USERNAME=user \
  MYSQL_PASSWORD=pass

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
|   +-- MssqlDataSourceConfig       Secondary datasource (conditional)
|   +-- WebSocketConfig             /ws/stats endpoint
|   +-- FileInitializer             Synthetic video generator
|
+-- entity/
|   +-- pg/PgVideoChunk             PostgreSQL entity (bytea)
|   +-- mssql/MssqlVideoChunk       MySQL entity (LONGBLOB)
|
+-- repository/
|   +-- pg/PgVideoChunkRepository
|   +-- mssql/MssqlVideoChunkRepository
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
  "mssqlEnabled": true,
  "mssqlRowCount": 21217,
  "mssqlSizeBytes": 21454405632,
  "mssqlInsertsPerSecond": 4.1,
  "mssqlReadsPerSecond": 4.1,
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
