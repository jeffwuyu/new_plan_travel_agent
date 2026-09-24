package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 中文注释：实体类，用于定义 Rag Chunk 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class RagChunk {

    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String chunkText;

    /** DashVector vector ID, set after upsert */
    private String dashvectorId;

    /** JSON fallback for the embedding vector when pgvector is not available. */
    private String embeddingJson;

    /** PostgreSQL pgvector literal, e.g. [0.1,0.2], used by postgres-rag.sql runtime schema. */
    private String embeddingVector;

    /** Text materialized for PostgreSQL Full-Text Search / fallback keyword search. */
    private String searchText;

    private String metadataJson;

    private Integer tokenCount;

    private LocalDateTime createdAt;
}
