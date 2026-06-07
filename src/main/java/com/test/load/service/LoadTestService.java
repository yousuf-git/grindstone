package com.test.load.service;

import com.test.load.entity.pg.PgVideoChunk;
import com.test.load.entity.mssql.MssqlVideoChunk;
import com.test.load.repository.pg.PgVideoChunkRepository;
import com.test.load.repository.mssql.MssqlVideoChunkRepository;
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

    private final PgVideoChunkRepository pgVideoChunkRepository;
    private final StatsService statsService;
    private final StatsWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private MssqlVideoChunkRepository mssqlVideoChunkRepository;

    @Value("${loadtest.threads:100}")
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

    @Value("${app.storage.mysql-max-bytes:0}")
    private long mssqlMaxBytes;

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
        boolean mssqlAvailable = mssqlVideoChunkRepository != null;
        statsService.setMssqlEnabled(mssqlAvailable);
        statsService.setPgMaxBytes(pgMaxBytes);
        statsService.setMssqlMaxBytes(mssqlMaxBytes);
        log.info("MSSQL database: {}", mssqlAvailable ? "ENABLED" : "DISABLED");

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
        statsService.setMssqlEnabled(mssqlVideoChunkRepository != null);
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

        log.info("Load test started: {} threads, {}MB chunks, file={}, mssql={}",
            threadCount, chunkSizeMb, videoPath, mssqlVideoChunkRepository != null);
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

                    // PostgreSQL write + read
                    try {
                        PgVideoChunk pgEntity = new PgVideoChunk();
                        pgEntity.setChunkData(chunk);
                        pgEntity.setHash(hash);
                        pgEntity.setThreadName(Thread.currentThread().getName());
                        pgEntity.setChunkSize(chunk.length);
                        pgEntity.setCreatedAt(LocalDateTime.now());
                        PgVideoChunk pgSaved = pgVideoChunkRepository.save(pgEntity);
                        statsService.incrementPgInserts();

                        PgVideoChunk pgFetched = pgVideoChunkRepository.findById(pgSaved.getId()).orElse(null);
                        statsService.incrementPgReads();

                        if (pgFetched != null) {
                            String verifyHash = computeHash(pgFetched.getChunkData(), hashingRounds);
                            if (!hash.equals(verifyHash)) {
                                statsService.incrementErrors();
                            }
                        }
                    } catch (Exception e) {
                        statsService.incrementErrors();
                        log.debug("PG operation failed", e);
                    }

                    // MSSQL write + read
                    if (mssqlVideoChunkRepository != null) {
                        try {
                            MssqlVideoChunk msEntity = new MssqlVideoChunk();
                            msEntity.setChunkData(chunk);
                            msEntity.setHash(hash);
                            msEntity.setThreadName(Thread.currentThread().getName());
                            msEntity.setChunkSize(chunk.length);
                            msEntity.setCreatedAt(LocalDateTime.now());
                            MssqlVideoChunk msSaved = mssqlVideoChunkRepository.save(msEntity);
                            statsService.incrementMssqlInserts();

                            MssqlVideoChunk msFetched = mssqlVideoChunkRepository.findById(msSaved.getId()).orElse(null);
                            statsService.incrementMssqlReads();

                            if (msFetched != null) {
                                String verifyHash = computeHash(msFetched.getChunkData(), hashingRounds);
                                if (!hash.equals(verifyHash)) {
                                    statsService.incrementErrors();
                                }
                            }
                        } catch (Exception e) {
                            statsService.incrementErrors();
                            log.debug("MSSQL operation failed", e);
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
        try {
            statsService.setPgRowCount(pgVideoChunkRepository.count());
            statsService.setPgSizeBytes(pgVideoChunkRepository.getTableSizeBytes());
        } catch (Exception e) {
            log.debug("Failed to poll PG stats", e);
        }

        if (mssqlVideoChunkRepository != null) {
            try {
                statsService.setMssqlRowCount(mssqlVideoChunkRepository.count());
                statsService.setMssqlSizeBytes(mssqlVideoChunkRepository.getTableSizeBytes());
            } catch (Exception e) {
                log.debug("Failed to poll MSSQL stats", e);
            }
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
