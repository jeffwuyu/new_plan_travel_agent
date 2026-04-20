package com.travelagent.mapper;

import com.travelagent.model.entity.RagChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 中文注释：Mapper 接口，负责 rag_chunks 表的数据访问与持久化映射。
 */
@Mapper
public interface RagChunkMapper {

    /** Batch-inserts a list of chunks. Returns the number of rows inserted. */
    int insertBatch(@Param("chunks") List<RagChunk> chunks);

    /** Returns all chunks for a document, ordered by chunk_index ascending. */
    List<RagChunk> findByDocumentId(@Param("documentId") Long documentId);

    /** Finds a chunk by its DashVector vector ID. Returns null if not found. */
    RagChunk findByDashvectorId(@Param("dashvectorId") String dashvectorId);

    /** Deletes all chunks belonging to a document. Returns row count. */
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
