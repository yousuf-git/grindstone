package com.test.load.controller;

import com.test.load.service.LoadTestService;
import com.test.load.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping(value = "/api", produces = "application/json")
public class LoadTestController {

    private final LoadTestService loadTestService;
    private final StatsService statsService;

    public LoadTestController(LoadTestService loadTestService, StatsService statsService) {
        this.loadTestService = loadTestService;
        this.statsService = statsService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) Map<String, Integer> config) {
        try {
            Integer threads = config != null ? config.get("threads") : null;
            Integer chunkMb = config != null ? config.get("chunkSizeMb") : null;
            String result = loadTestService.start(threads, chunkMb);
            boolean ok = "Started".equals(result);
            return ResponseEntity.ok(Map.of("status", ok ? "started" : "error", "message", result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        try {
            String result = loadTestService.stop();
            return ResponseEntity.ok(Map.of("status", "stopped", "message", result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(statsService.getSnapshot());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(Map.of(
            "threads", loadTestService.getThreadCount(),
            "chunkSizeMb", loadTestService.getChunkSizeMb(),
            "videoPath", loadTestService.getVideoPath()
        ));
    }
}
