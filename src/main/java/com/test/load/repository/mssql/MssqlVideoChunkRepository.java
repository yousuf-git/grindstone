package com.test.load.repository.mssql;

import com.test.load.entity.mssql.MssqlVideoChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MssqlVideoChunkRepository extends JpaRepository<MssqlVideoChunk, Long> {

    @Query(value = "SELECT SUM(data_length + index_length) FROM information_schema.tables WHERE table_schema = DATABASE()", nativeQuery = true)
    long getTableSizeBytes();
}
