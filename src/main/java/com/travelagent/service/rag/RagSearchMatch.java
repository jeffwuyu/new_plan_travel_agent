package com.travelagent.service.rag;

import lombok.Data;

/**
 * RAG 原始召回行，承载向量检索或关键词检索在融合排序前返回的数据库字段。
 */
@Data
public class RagSearchMatch {

    private Long chunkId;
    private Long documentId;
    private String chunkText;
    private String title;
    private String region;
    private String sourceType;
    private String sourceName;
    private String sourceUrl;
    private String locatorJson;
    private String indexVersion;
    private Double vectorScore;
    private Double bm25Score;
    private Double freshnessScore;
}
