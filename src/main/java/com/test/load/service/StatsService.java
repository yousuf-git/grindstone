package com.test.load.service;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class StatsService {

    /** Per-database counters and gauges. */
    public static class DbStats {
        final String key;
        final String label;
        final boolean active; // true = participates in write/read load; false = stats-only (stopped)
        final AtomicLong inserts = new AtomicLong();
        final AtomicLong reads = new AtomicLong();
        volatile boolean enabled = false;
        volatile long rowCount = 0;
        volatile long sizeBytes = 0;
        volatile long maxBytes = 0;
        long lastInserts = 0;
        long lastReads = 0;

        DbStats(String key, String label, boolean active) {
            this.key = key;
            this.label = label;
            this.active = active;
        }
    }

    private final Map<String, DbStats> dbs = new LinkedHashMap<>();

    private final AtomicLong totalOps = new AtomicLong();
    private final AtomicLong totalBytesProcessed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicInteger activeThreads = new AtomicInteger();

    private volatile boolean running = false;
    private volatile long startTime = 0;
    private volatile int threadCount = 20;
    private volatile int chunkSizeMb = 10;
    private volatile String videoPath = "";
    private volatile long videoSize = 0;

    private long lastOps, lastBytes;
    private long lastTimestamp = System.currentTimeMillis();

    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    public StatsService() {
        this.osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    /** Register a database. Order of registration = display order. */
    public void registerDb(String key, String label, boolean active) {
        dbs.computeIfAbsent(key, k -> new DbStats(key, label, active));
    }

    private DbStats db(String key) {
        DbStats d = dbs.get(key);
        if (d == null) throw new IllegalArgumentException("Unknown db key: " + key);
        return d;
    }

    public void incrementTotalOps() { totalOps.incrementAndGet(); }
    public void incrementErrors() { errors.incrementAndGet(); }
    public void addBytesProcessed(long bytes) { totalBytesProcessed.addAndGet(bytes); }
    public void incrementActiveThreads() { activeThreads.incrementAndGet(); }
    public void decrementActiveThreads() { activeThreads.decrementAndGet(); }

    public void incrementInserts(String key) { db(key).inserts.incrementAndGet(); }
    public void incrementReads(String key) { db(key).reads.incrementAndGet(); }
    public void setEnabled(String key, boolean enabled) { db(key).enabled = enabled; }
    public void setRowCount(String key, long count) { db(key).rowCount = count; }
    public void setSizeBytes(String key, long size) { db(key).sizeBytes = size; }
    public void setMaxBytes(String key, long size) { db(key).maxBytes = size; }

    public void setRunning(boolean running) { this.running = running; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }
    public void setChunkSizeMb(int chunkSizeMb) { this.chunkSizeMb = chunkSizeMb; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
    public void setVideoSize(long videoSize) { this.videoSize = videoSize; }
    public boolean isRunning() { return running; }

    public void reset() {
        totalOps.set(0);
        totalBytesProcessed.set(0);
        errors.set(0);
        activeThreads.set(0);
        lastOps = 0;
        lastBytes = 0;
        for (DbStats d : dbs.values()) {
            d.inserts.set(0);
            d.reads.set(0);
            d.lastInserts = 0;
            d.lastReads = 0;
        }
        lastTimestamp = System.currentTimeMillis();
    }

    public Map<String, Object> getSnapshot() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTimestamp;
        if (elapsed <= 0) elapsed = 1;
        double factor = 1000.0 / elapsed;

        long curOps = totalOps.get();
        long curBytes = totalBytesProcessed.get();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cpuUsage", Math.round(osMxBean.getCpuLoad() * 1000.0) / 10.0);
        stats.put("processCpuUsage", Math.round(osMxBean.getProcessCpuLoad() * 1000.0) / 10.0);

        long heapUsed = memoryMxBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryMxBean.getHeapMemoryUsage().getMax();
        long nonHeapUsed = memoryMxBean.getNonHeapMemoryUsage().getUsed();

        stats.put("heapUsed", heapUsed);
        stats.put("heapMax", heapMax);
        stats.put("nonHeapUsed", nonHeapUsed);

        stats.put("totalOps", curOps);
        stats.put("opsPerSecond", Math.round((curOps - lastOps) * factor * 10.0) / 10.0);

        // Per-database stats as ordered list
        List<Map<String, Object>> dbList = new ArrayList<>();
        for (DbStats d : dbs.values()) {
            long curIns = d.inserts.get();
            long curRds = d.reads.get();

            Map<String, Object> dbMap = new LinkedHashMap<>();
            dbMap.put("key", d.key);
            dbMap.put("label", d.label);
            dbMap.put("active", d.active);
            dbMap.put("enabled", d.enabled);
            dbMap.put("insertsPerSecond", Math.round((curIns - d.lastInserts) * factor * 10.0) / 10.0);
            dbMap.put("readsPerSecond", Math.round((curRds - d.lastReads) * factor * 10.0) / 10.0);
            dbMap.put("rowCount", d.rowCount);
            dbMap.put("sizeBytes", d.sizeBytes);
            dbMap.put("maxBytes", d.maxBytes);
            dbList.add(dbMap);

            d.lastInserts = curIns;
            d.lastReads = curRds;
        }
        stats.put("databases", dbList);

        stats.put("bytesPerSecond", Math.round((curBytes - lastBytes) * factor));
        stats.put("totalBytesProcessed", curBytes);
        stats.put("activeThreads", activeThreads.get());
        stats.put("errors", errors.get());
        stats.put("running", running);
        stats.put("threadCount", threadCount);
        stats.put("chunkSizeMb", chunkSizeMb);
        stats.put("videoPath", videoPath);
        stats.put("videoSize", videoSize);

        if (running && startTime > 0) {
            stats.put("uptimeSeconds", (now - startTime) / 1000);
        } else {
            stats.put("uptimeSeconds", 0);
        }

        lastOps = curOps;
        lastBytes = curBytes;
        lastTimestamp = now;

        return stats;
    }
}
