package com.test.load.repository.pg3;

import com.test.load.entity.pg3.Pg3VideoChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface Pg3VideoChunkRepository extends JpaRepository<Pg3VideoChunk, Long> {

    @Query(value = "SELECT pg_database_size(current_database())", nativeQuery = true)
    long getTableSizeBytes();
}
