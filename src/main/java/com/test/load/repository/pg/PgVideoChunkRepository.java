package com.test.load.repository.pg;

import com.test.load.entity.pg.PgVideoChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PgVideoChunkRepository extends JpaRepository<PgVideoChunk, Long> {

    @Query(value = "SELECT pg_database_size(current_database())", nativeQuery = true)
    long getTableSizeBytes();
}
