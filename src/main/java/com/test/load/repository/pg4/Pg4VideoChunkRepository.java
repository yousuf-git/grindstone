package com.test.load.repository.pg4;

import com.test.load.entity.pg4.Pg4VideoChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface Pg4VideoChunkRepository extends JpaRepository<Pg4VideoChunk, Long> {

    @Query(value = "SELECT pg_database_size(current_database())", nativeQuery = true)
    long getTableSizeBytes();
}
