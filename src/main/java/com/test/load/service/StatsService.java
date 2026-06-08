package com.test.load.service;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class StatsService {

    private final AtomicLong totalOps = new AtomicLong();
    private final AtomicLong pgInserts = new AtomicLong();
    private final AtomicLong pgReads = new AtomicLong();
    private final AtomicLong pg2Inserts = new AtomicLong();
    private final AtomicLong pg2Reads = new AtomicLong();
    private final AtomicLong totalBytesProcessed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicInteger activeThreads = new AtomicInteger();

    private volatile boolean running = false;
    private volatile boolean pg2Enabled = false;
    private volatile long startTime = 0;
    private volatile int threadCount = 100;
    private volatile int chunkSizeMb = 10;
    private volatile String videoPath = "";
    private volatile long videoSize = 0;
    private volatile long pgRowCount = 0;
    private volatile long pgSizeBytes = 0;
    private volatile long pg2RowCount = 0;
    private volatile long pg2SizeBytes = 0;
    private volatile long pgMaxBytes = 0;
    private volatile long pg2MaxBytes = 0;

    private long lastOps, lastPgInserts, lastPgReads, lastPg2Inserts, lastPg2Reads, lastBytes;
    private long lastTimestamp = System.currentTimeMillis();

    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    public StatsService() {
        this.osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    public void incrementTotalOps() { totalOps.incrementAndGet(); }
    public void incrementPgInserts() { pgInserts.incrementAndGet(); }
    public void incrementPgReads() { pgReads.incrementAndGet(); }
    public void incrementPg2Inserts() { pg2Inserts.incrementAndGet(); }
    public void incrementPg2Reads() { pg2Reads.incrementAndGet(); }
    public void incrementErrors() { errors.incrementAndGet(); }
    public void addBytesProcessed(long bytes) { totalBytesProcessed.addAndGet(bytes); }
    public void incrementActiveThreads() { activeThreads.incrementAndGet(); }
    public void decrementActiveThreads() { activeThreads.decrementAndGet(); }

    public void setRunning(boolean running) { this.running = running; }
    public void setPg2Enabled(boolean enabled) { this.pg2Enabled = enabled; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }
    public void setChunkSizeMb(int chunkSizeMb) { this.chunkSizeMb = chunkSizeMb; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
    public void setVideoSize(long videoSize) { this.videoSize = videoSize; }
    public void setPgRowCount(long count) { this.pgRowCount = count; }
    public void setPgSizeBytes(long size) { this.pgSizeBytes = size; }
    public void setPg2RowCount(long count) { this.pg2RowCount = count; }
    public void setPg2SizeBytes(long size) { this.pg2SizeBytes = size; }
    public void setPgMaxBytes(long size) { this.pgMaxBytes = size; }
    public void setPg2MaxBytes(long size) { this.pg2MaxBytes = size; }
    public boolean isRunning() { return running; }

    public void reset() {
        totalOps.set(0);
        pgInserts.set(0);
        pgReads.set(0);
        pg2Inserts.set(0);
        pg2Reads.set(0);
        totalBytesProcessed.set(0);
        errors.set(0);
        activeThreads.set(0);
        lastOps = 0;
        lastPgInserts = 0;
        lastPgReads = 0;
        lastPg2Inserts = 0;
        lastPg2Reads = 0;
        lastBytes = 0;
        lastTimestamp = System.currentTimeMillis();
    }

    public Map<String, Object> getSnapshot() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTimestamp;
        if (elapsed <= 0) elapsed = 1;
        double factor = 1000.0 / elapsed;

        long curOps = totalOps.get();
        long curPgIns = pgInserts.get();
        long curPgRds = pgReads.get();
        long curPg2Ins = pg2Inserts.get();
        long curPg2Rds = pg2Reads.get();
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

        stats.put("pgInsertsPerSecond", Math.round((curPgIns - lastPgInserts) * factor * 10.0) / 10.0);
        stats.put("pgReadsPerSecond", Math.round((curPgRds - lastPgReads) * factor * 10.0) / 10.0);
        stats.put("pgRowCount", pgRowCount);
        stats.put("pgSizeBytes", pgSizeBytes);
        stats.put("pgMaxBytes", pgMaxBytes);

        stats.put("pg2Enabled", pg2Enabled);
        stats.put("pg2InsertsPerSecond", Math.round((curPg2Ins - lastPg2Inserts) * factor * 10.0) / 10.0);
        stats.put("pg2ReadsPerSecond", Math.round((curPg2Rds - lastPg2Reads) * factor * 10.0) / 10.0);
        stats.put("pg2RowCount", pg2RowCount);
        stats.put("pg2SizeBytes", pg2SizeBytes);
        stats.put("pg2MaxBytes", pg2MaxBytes);

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
        lastPgInserts = curPgIns;
        lastPgReads = curPgRds;
        lastPg2Inserts = curPg2Ins;
        lastPg2Reads = curPg2Rds;
        lastBytes = curBytes;
        lastTimestamp = now;

        return stats;
    }
}
