package com.test.load.repository.pg5;

import com.test.load.entity.pg5.Pg5VideoChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface Pg5VideoChunkRepository extends JpaRepository<Pg5VideoChunk, Long> {

    @Query(value = "SELECT pg_database_size(current_database())", nativeQuery = true)
    long getTableSizeBytes();
}
