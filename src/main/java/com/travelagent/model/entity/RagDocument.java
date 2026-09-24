package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 中文注释：实体类，用于定义 Rag Document 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class RagDocument {

    private Long id;

    /** OSS object key path */
    private String ossKey;

    private String title;

    private String region;

    /** pdf | markdown | text */
    private String docType;

    /** static_knowledge | user_preference; real-time facts must stay in tools */
    private String sourceType;

    /** manual | controlled_crawl | profile_summary, etc. */
    private String sourceName;

    private String sourceUrl;

    private String metadataJson;

    /** pending | indexing | indexed | failed | disabled */
    private String status;

    /** 0-100 ingest progress for admin visibility. */
    private Integer ingestProgress;

    /** Manual retry count for rebuild/reingest operations. */
    private Integer retryCount;

    /** Structured ingest failure code for admin governance. */
    private String lastErrorCode;

    private String errorMessage;

    private LocalDateTime disabledAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
