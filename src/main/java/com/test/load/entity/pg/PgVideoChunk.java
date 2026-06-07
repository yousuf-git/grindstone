package com.test.load.entity.pg;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "video_chunk")
public class PgVideoChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "bytea")
    private byte[] chunkData;

    private String hash;

    private String threadName;

    private int chunkSize;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public byte[] getChunkData() { return chunkData; }
    public void setChunkData(byte[] chunkData) { this.chunkData = chunkData; }

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
