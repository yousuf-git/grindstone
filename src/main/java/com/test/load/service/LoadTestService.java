package com.test.load.service;

import com.test.load.entity.pg3.Pg3VideoChunk;
import com.test.load.entity.pg4.Pg4VideoChunk;
import com.test.load.entity.pg5.Pg5VideoChunk;
import com.test.load.repository.pg.PgVideoChunkRepository;
import com.test.load.repository.pg2.Pg2VideoChunkRepository;
import com.test.load.repository.pg3.Pg3VideoChunkRepository;
import com.test.load.repository.pg4.Pg4VideoChunkRepository;
import com.test.load.repository.pg5.Pg5VideoChunkRepository;
import com.test.load.websocket.StatsWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoadTestService {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);

    private final StatsService statsService;
    private final StatsWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    // Stopped databases (stats-only, no writes)
    private final PgVideoChunkRepository pgVideoChunkRepository;

    @Autowired(required = false)
    private Pg2VideoChunkRepository pg2VideoChunkRepository;

    // Active databases (full write/read load)
    @Autowired(required = false)
    private Pg3VideoChunkRepository pg3VideoChunkRepository;

    @Autowired(required = false)
    private Pg4VideoChunkRepository pg4VideoChunkRepository;

    @Autowired(required = false)
    private Pg5VideoChunkRepository pg5VideoChunkRepository;

    @Value("${loadtest.threads:20}")
    private int threadCount;

    @Value("${loadtest.chunk-size-mb:10}")
    private int chunkSizeMb;

    @Value("${loadtest.video-path:./data/video.mp4}")
    private String videoPath;

    @Value("${loadtest.hashing-rounds:5}")
    private int hashingRounds;

    @Value("${loadtest.matrix-size:200}")
    private int matrixSize;

    @Value("${loadtest.memory-pressure-mb:500}")
    private int memoryPressureMb;

    @Value("${loadtest.auto-start:false}")
    private boolean autoStart;

    @Value("${app.storage.pg-max-bytes:0}")
    private long pgMaxBytes;

    @Value("${app.storage.pg2-max-bytes:0}")
    private long pg2MaxBytes;

    @Value("${app.storage.pg3-max-bytes:0}")
    private long pg3MaxBytes;

    @Value("${app.storage.pg4-max-bytes:0}")
    private long pg4MaxBytes;

    @Value("${app.storage.pg5-max-bytes:0}")
    private long pg5MaxBytes;

    private ExecutorService workerPool;
    private ScheduledExecutorService statsScheduler;
    private ScheduledExecutorService dbPollScheduler;
    private volatile boolean running = false;
    private long videoFileSize = 0;

    private byte[][] memoryPool;
    private final AtomicInteger memoryPoolIndex = new AtomicInteger(0);

    public LoadTestService(PgVideoChunkRepository pgVideoChunkRepository,
                           StatsService statsService,
                           StatsWebSocketHandler webSocketHandler,
                           ObjectMapper objectMapper) {
        this.pgVideoChunkRepository = pgVideoChunkRepository;
        this.statsService = statsService;
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Register databases in display order. Stopped first, then active.
        statsService.registerDb("pg", "PG1", false);
        statsService.registerDb("pg2", "PG2", false);
        statsService.registerDb("pg3", "Purple", true);
        statsService.registerDb("pg4", "Amber", true);
        statsService.registerDb("pg5", "Olive", true);

        statsService.setEnabled("pg", true);
        statsService.setEnabled("pg2", pg2VideoChunkRepository != null);
        statsService.setEnabled("pg3", pg3VideoChunkRepository != null);
        statsService.setEnabled("pg4", pg4VideoChunkRepository != null);
        statsService.setEnabled("pg5", pg5VideoChunkRepository != null);

        statsService.setMaxBytes("pg", pgMaxBytes);
        statsService.setMaxBytes("pg2", pg2MaxBytes);
        statsService.setMaxBytes("pg3", pg3MaxBytes);
        statsService.setMaxBytes("pg4", pg4MaxBytes);
        statsService.setMaxBytes("pg5", pg5MaxBytes);

        log.info("Databases — stopped: PG1=true, PG2={}; active: Purple={}, Amber={}, Olive={}",
            pg2VideoChunkRepository != null,
            pg3VideoChunkRepository != null, pg4VideoChunkRepository != null, pg5VideoChunkRepository != null);

        statsScheduler = Executors.newSingleThreadScheduledExecutor();
        statsScheduler.scheduleAtFixedRate(this::pushStats, 0, 1, TimeUnit.SECONDS);

        dbPollScheduler = Executors.newSingleThreadScheduledExecutor();
        dbPollScheduler.scheduleAtFixedRate(this::pollDbStats, 2, 5, TimeUnit.SECONDS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (autoStart) {
            log.info("Auto-starting load test");
            start(null, null);
        }
    }

    @PreDestroy
    public void destroy() {
        if (statsScheduler != null) statsScheduler.shutdownNow();
        if (dbPollScheduler != null) dbPollScheduler.shutdownNow();
    }

    public synchronized String start(Integer threads, Integer chunkMb) {
        if (running) return "Already running";

        if (threads != null && threads > 0) this.threadCount = threads;
        if (chunkMb != null && chunkMb > 0) this.chunkSizeMb = chunkMb;

        File videoFile = Path.of(videoPath).toFile();
        if (!videoFile.exists()) {
            return "Video file not found: " + videoFile.getAbsolutePath();
        }
        videoFileSize = videoFile.length();

        running = true;
        statsService.reset();
        statsService.setRunning(true);
        statsService.setStartTime(System.currentTimeMillis());
        statsService.setThreadCount(threadCount);
        statsService.setChunkSizeMb(chunkSizeMb);
        statsService.setVideoPath(videoPath);
        statsService.setVideoSize(videoFileSize);

        memoryPool = new byte[memoryPressureMb][];

        workerPool = Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "load-worker-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        for (int i = 0; i < threadCount; i++) {
            workerPool.submit(this::workerLoop);
        }

        log.info("Load test started: {} threads, {}MB chunks, file={}, active DBs: pg3={}, pg4={}, pg5={}",
            threadCount, chunkSizeMb, videoPath,
            pg3VideoChunkRepository != null, pg4VideoChunkRepository != null, pg5VideoChunkRepository != null);
        return "Started";
    }

    public synchronized String stop() {
        if (!running) return "Not running";

        running = false;
        statsService.setRunning(false);

        if (workerPool != null) {
            workerPool.shutdownNow();
            workerPool = null;
        }
        memoryPool = null;

        log.info("Load test stopped");
        return "Stopped";
    }

    private void workerLoop() {
        statsService.incrementActiveThreads();
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] chunk = readRandomChunk();
                    statsService.addBytesProcessed(chunk.length);

                    String hash = computeHash(chunk, hashingRounds);

                    // Active DB writes/reads (Purple / Amber / Olive)
                    if (pg3VideoChunkRepository != null) {
                        try {
                            Pg3VideoChunk e = new Pg3VideoChunk();
                            e.setChunkData(chunk);
                            e.setHash(hash);
                            e.setThreadName(Thread.currentThread().getName());
                            e.setChunkSize(chunk.length);
                            e.setCreatedAt(LocalDateTime.now());
                            Pg3VideoChunk saved = pg3VideoChunkRepository.save(e);
                            statsService.incrementInserts("pg3");

                            Pg3VideoChunk fetched = pg3VideoChunkRepository.findById(saved.getId()).orElse(null);
                            statsService.incrementReads("pg3");
                            if (fetched != null && !hash.equals(computeHash(fetched.getChunkData(), hashingRounds))) {
                                statsService.incrementErrors();
                            }
                        } catch (Exception ex) {
                            statsService.incrementErrors();
                            log.debug("Purple (pg3) operation failed", ex);
                        }
                    }

                    if (pg4VideoChunkRepository != null) {
                        try {
                            Pg4VideoChunk e = new Pg4VideoChunk();
                            e.setChunkData(chunk);
                            e.setHash(hash);
                            e.setThreadName(Thread.currentThread().getName());
                            e.setChunkSize(chunk.length);
                            e.setCreatedAt(LocalDateTime.now());
                            Pg4VideoChunk saved = pg4VideoChunkRepository.save(e);
                            statsService.incrementInserts("pg4");

                            Pg4VideoChunk fetched = pg4VideoChunkRepository.findById(saved.getId()).orElse(null);
                            statsService.incrementReads("pg4");
                            if (fetched != null && !hash.equals(computeHash(fetched.getChunkData(), hashingRounds))) {
                                statsService.incrementErrors();
                            }
                        } catch (Exception ex) {
                            statsService.incrementErrors();
                            log.debug("Amber (pg4) operation failed", ex);
                        }
                    }

                    if (pg5VideoChunkRepository != null) {
                        try {
                            Pg5VideoChunk e = new Pg5VideoChunk();
                            e.setChunkData(chunk);
                            e.setHash(hash);
                            e.setThreadName(Thread.currentThread().getName());
                            e.setChunkSize(chunk.length);
                            e.setCreatedAt(LocalDateTime.now());
                            Pg5VideoChunk saved = pg5VideoChunkRepository.save(e);
                            statsService.incrementInserts("pg5");

                            Pg5VideoChunk fetched = pg5VideoChunkRepository.findById(saved.getId()).orElse(null);
                            statsService.incrementReads("pg5");
                            if (fetched != null && !hash.equals(computeHash(fetched.getChunkData(), hashingRounds))) {
                                statsService.incrementErrors();
                            }
                        } catch (Exception ex) {
                            statsService.incrementErrors();
                            log.debug("Olive (pg5) operation failed", ex);
                        }
                    }

                    doMatrixWork();

                    allocateMemoryPressure();

                    statsService.incrementTotalOps();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    statsService.incrementErrors();
                    if (!running) break;
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            statsService.decrementActiveThreads();
        }
    }

    private byte[] readRandomChunk() throws Exception {
        int chunkBytes = chunkSizeMb * 1024 * 1024;
        long maxOffset = Math.max(0, videoFileSize - chunkBytes);
        long offset = ThreadLocalRandom.current().nextLong(maxOffset + 1);

        int toRead = (int) Math.min(chunkBytes, videoFileSize - offset);
        byte[] buffer = new byte[toRead];

        try (RandomAccessFile raf = new RandomAccessFile(videoPath, "r")) {
            raf.seek(offset);
            raf.readFully(buffer);
        }
        return buffer;
    }

    private String computeHash(byte[] data, int rounds) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = data;
        for (int i = 0; i < rounds; i++) {
            hash = md.digest(hash);
        }
        return HexFormat.of().formatHex(hash);
    }

    private void doMatrixWork() {
        int size = matrixSize;
        double[][] a = new double[size][size];
        double[][] b = new double[size][size];
        double[][] c = new double[size][size];
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                a[i][j] = rand.nextDouble();
                b[i][j] = rand.nextDouble();
            }
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double sum = 0;
                for (int k = 0; k < size; k++) {
                    sum += a[i][k] * b[k][j];
                }
                c[i][j] = sum;
            }
        }
    }

    private void allocateMemoryPressure() {
        if (memoryPool == null) return;
        int idx = memoryPoolIndex.getAndIncrement() % memoryPool.length;
        byte[] buffer = new byte[1024 * 1024];
        ThreadLocalRandom.current().nextBytes(buffer);
        memoryPool[idx] = buffer;
    }

    private void pollDbStats() {
        pollOne("pg", pgVideoChunkRepository::count, pgVideoChunkRepository::getTableSizeBytes);
        if (pg2VideoChunkRepository != null) {
            pollOne("pg2", pg2VideoChunkRepository::count, pg2VideoChunkRepository::getTableSizeBytes);
        }
        if (pg3VideoChunkRepository != null) {
            pollOne("pg3", pg3VideoChunkRepository::count, pg3VideoChunkRepository::getTableSizeBytes);
        }
        if (pg4VideoChunkRepository != null) {
            pollOne("pg4", pg4VideoChunkRepository::count, pg4VideoChunkRepository::getTableSizeBytes);
        }
        if (pg5VideoChunkRepository != null) {
            pollOne("pg5", pg5VideoChunkRepository::count, pg5VideoChunkRepository::getTableSizeBytes);
        }
    }

    private void pollOne(String key, java.util.function.LongSupplier count, java.util.function.LongSupplier size) {
        try {
            statsService.setRowCount(key, count.getAsLong());
            statsService.setSizeBytes(key, size.getAsLong());
        } catch (Exception e) {
            log.debug("Failed to poll {} stats", key, e);
        }
    }

    private void pushStats() {
        try {
            String json = objectMapper.writeValueAsString(statsService.getSnapshot());
            webSocketHandler.broadcast(json);
        } catch (Exception e) {
            log.debug("Failed to push stats", e);
        }
    }

    public boolean isRunning() { return running; }
    public int getThreadCount() { return threadCount; }
    public int getChunkSizeMb() { return chunkSizeMb; }
    public String getVideoPath() { return videoPath; }
}
