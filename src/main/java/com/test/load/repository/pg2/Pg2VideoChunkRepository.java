package com.test.load.repository.pg2;

import com.test.load.entity.pg2.Pg2VideoChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface Pg2VideoChunkRepository extends JpaRepository<Pg2VideoChunk, Long> {

    @Query(value = "SELECT pg_database_size(current_database())", nativeQuery = true)
    long getTableSizeBytes();
}
