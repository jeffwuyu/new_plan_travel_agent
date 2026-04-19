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

    /** pending | indexed | failed */
    private String status;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
