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
    private final AtomicLong mssqlInserts = new AtomicLong();
    private final AtomicLong mssqlReads = new AtomicLong();
    private final AtomicLong totalBytesProcessed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicInteger activeThreads = new AtomicInteger();

    private volatile boolean running = false;
    private volatile boolean mssqlEnabled = false;
    private volatile long startTime = 0;
    private volatile int threadCount = 100;
    private volatile int chunkSizeMb = 10;
    private volatile String videoPath = "";
    private volatile long videoSize = 0;
    private volatile long pgRowCount = 0;
    private volatile long pgSizeBytes = 0;
    private volatile long mssqlRowCount = 0;
    private volatile long mssqlSizeBytes = 0;
    private volatile long pgMaxBytes = 0;
    private volatile long mssqlMaxBytes = 0;

    private long lastOps, lastPgInserts, lastPgReads, lastMssqlInserts, lastMssqlReads, lastBytes;
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
    public void incrementMssqlInserts() { mssqlInserts.incrementAndGet(); }
    public void incrementMssqlReads() { mssqlReads.incrementAndGet(); }
    public void incrementErrors() { errors.incrementAndGet(); }
    public void addBytesProcessed(long bytes) { totalBytesProcessed.addAndGet(bytes); }
    public void incrementActiveThreads() { activeThreads.incrementAndGet(); }
    public void decrementActiveThreads() { activeThreads.decrementAndGet(); }

    public void setRunning(boolean running) { this.running = running; }
    public void setMssqlEnabled(boolean enabled) { this.mssqlEnabled = enabled; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }
    public void setChunkSizeMb(int chunkSizeMb) { this.chunkSizeMb = chunkSizeMb; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
    public void setVideoSize(long videoSize) { this.videoSize = videoSize; }
    public void setPgRowCount(long count) { this.pgRowCount = count; }
    public void setPgSizeBytes(long size) { this.pgSizeBytes = size; }
    public void setMssqlRowCount(long count) { this.mssqlRowCount = count; }
    public void setMssqlSizeBytes(long size) { this.mssqlSizeBytes = size; }
    public void setPgMaxBytes(long size) { this.pgMaxBytes = size; }
    public void setMssqlMaxBytes(long size) { this.mssqlMaxBytes = size; }
    public boolean isRunning() { return running; }

    public void reset() {
        totalOps.set(0);
        pgInserts.set(0);
        pgReads.set(0);
        mssqlInserts.set(0);
        mssqlReads.set(0);
        totalBytesProcessed.set(0);
        errors.set(0);
        activeThreads.set(0);
        lastOps = 0;
        lastPgInserts = 0;
        lastPgReads = 0;
        lastMssqlInserts = 0;
        lastMssqlReads = 0;
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
        long curMsIns = mssqlInserts.get();
        long curMsRds = mssqlReads.get();
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

        stats.put("mssqlEnabled", mssqlEnabled);
        stats.put("mssqlInsertsPerSecond", Math.round((curMsIns - lastMssqlInserts) * factor * 10.0) / 10.0);
        stats.put("mssqlReadsPerSecond", Math.round((curMsRds - lastMssqlReads) * factor * 10.0) / 10.0);
        stats.put("mssqlRowCount", mssqlRowCount);
        stats.put("mssqlSizeBytes", mssqlSizeBytes);
        stats.put("mssqlMaxBytes", mssqlMaxBytes);

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
        lastMssqlInserts = curMsIns;
        lastMssqlReads = curMsRds;
        lastBytes = curBytes;
        lastTimestamp = now;

        return stats;
    }
}
