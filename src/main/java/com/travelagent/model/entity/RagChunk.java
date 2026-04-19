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

    private Integer tokenCount;

    private LocalDateTime createdAt;
}
