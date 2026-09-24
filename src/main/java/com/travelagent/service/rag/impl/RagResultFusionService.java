package com.travelagent.service.rag.impl;

import com.travelagent.service.rag.RagSearchResult;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 检索结果融合服务。
 *
 * <p>该组件负责把向量召回和关键词召回结果按 RRF 与业务权重融合，避免检索主流程
 * 同时承担排序细节。</p>
 */
@Component
public class RagResultFusionService {

    private static final int RRF_K = 60;

    /**
     * 融合向量和关键词召回结果。
     *
     * @param vectorResults 向量召回结果
     * @param keywordResults 关键词召回结果
     * @param topK 返回数量上限
     * @return 融合排序后的结果
     */
    public List<RagSearchResult> fuseResults(List<RagSearchResult> vectorResults,
                                             List<RagSearchResult> keywordResults,
                                             int topK) {
        Map<String, FusionBucket> buckets = new LinkedHashMap<>();
        addToFusionBuckets(buckets, vectorResults, true);
        addToFusionBuckets(buckets, keywordResults, false);

        return buckets.values().stream()
                .map(FusionBucket::toResult)
                .sorted(Comparator.comparing(RagSearchResult::finalScore).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 将单路召回结果加入融合桶。
     *
     * @param buckets 融合桶集合
     * @param results 单路召回结果
     * @param vectorLeg 是否为向量召回链路
     */
    private void addToFusionBuckets(Map<String, FusionBucket> buckets,
                                    List<RagSearchResult> results,
                                    boolean vectorLeg) {
        for (int i = 0; i < results.size(); i++) {
            RagSearchResult result = results.get(i);
            if (result == null || result.chunkText() == null || result.chunkText().isBlank()) {
                continue;
            }
            String key = result.chunkId() != null
                    ? "chunk:" + result.chunkId()
                    : "text:" + result.chunkText().trim().toLowerCase(Locale.ROOT);
            FusionBucket bucket = buckets.computeIfAbsent(key, ignored -> new FusionBucket(result));
            double reciprocalRank = 1.0d / (RRF_K + i + 1);
            if (vectorLeg) {
                bucket.vectorScore = Math.max(bucket.vectorScore, result.vectorScore());
                bucket.rrfScore += reciprocalRank;
            } else {
                bucket.bm25Score = Math.max(bucket.bm25Score, result.bm25Score());
                bucket.rrfScore += reciprocalRank;
            }
            bucket.freshnessScore = Math.max(bucket.freshnessScore, result.freshnessScore());
        }
    }

    /**
     * 格式化分数用于 rankReason。
     *
     * @param value 分数
     * @return 四位小数字符串
     */
    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * 单个 chunk 的融合累积桶。
     */
    private class FusionBucket {
        private final RagSearchResult base;
        private double vectorScore;
        private double bm25Score;
        private double freshnessScore;
        private double rrfScore;

        /**
         * 创建融合桶。
         *
         * @param base 首次命中的基础结果
         */
        private FusionBucket(RagSearchResult base) {
            this.base = base;
            this.vectorScore = base.vectorScore();
            this.bm25Score = base.bm25Score();
            this.freshnessScore = base.freshnessScore();
        }

        /**
         * 转换为最终检索结果。
         *
         * @return 带最终分数和排序原因的结果
         */
        private RagSearchResult toResult() {
            double finalScore = 0.45d * vectorScore
                    + 0.35d * bm25Score
                    + 0.15d * rrfScore
                    + 0.05d * freshnessScore;
            String rankReason = "hybrid: vectorScore=" + formatScore(vectorScore)
                    + ", bm25Score=" + formatScore(bm25Score)
                    + ", rrf=" + formatScore(rrfScore)
                    + ", sourceType=" + base.sourceType();
            return new RagSearchResult(
                    base.chunkId(),
                    base.documentId(),
                    base.chunkText(),
                    base.title(),
                    base.region(),
                    base.sourceType(),
                    base.sourceName(),
                    base.sourceUrl(),
                    vectorScore,
                    bm25Score,
                    freshnessScore,
                    finalScore,
                    rankReason
            );
        }
    }
}
