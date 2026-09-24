package com.travelagent.service.rag;

/**
 * RAG 混合检索结果，包含来源信息、各路召回分数和最终融合排序原因。
 *
 * @param chunkId chunk ID
 * @param documentId 文档 ID
 * @param chunkText chunk 文本
 * @param title 文档标题
 * @param region 区域标签
 * @param sourceType 来源类型
 * @param sourceName 来源名称
 * @param sourceUrl 来源 URL
 * @param vectorScore 向量召回分数
 * @param bm25Score 关键词召回分数
 * @param freshnessScore 新鲜度分数
 * @param finalScore 最终融合分数
 * @param rankReason 排序原因说明
 */
public record RagSearchResult(
        Long chunkId,
        Long documentId,
        String chunkText,
        String title,
        String region,
        String sourceType,
        String sourceName,
        String sourceUrl,
        double vectorScore,
        double bm25Score,
        double freshnessScore,
        double finalScore,
        String rankReason
) {
}
