package com.travelagent.service.rag.impl;

import com.travelagent.client.dashvector.DashVectorClient;
import com.travelagent.mapper.RagChunkMapper;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.entity.RagChunk;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.EmbeddingService;
import com.travelagent.service.rag.RagSearchMatch;
import com.travelagent.service.rag.RagSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RAG 召回服务，封装向量召回、关键词召回、DashVector 降级和检索后端选择。
 */
@Component
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private static final String VECTOR_BACKEND_POSTGRES = "postgres";
    private static final String VECTOR_BACKEND_DASHVECTOR = "dashvector";
    private static final String VECTOR_BACKEND_POSTGRES_WITH_FALLBACK = "postgres_with_dashvector_fallback";
    private static final Set<String> RAG_SOURCE_TYPES = Set.of("static_knowledge", "user_preference");

    private final DashVectorClient dashVectorClient;
    private final EmbeddingService embeddingService;
    private final RagDocumentMapper ragDocumentMapper;
    private final RagChunkMapper ragChunkMapper;

    @Value("${rag.vector-backend:postgres}")
    private String vectorBackend;

    /**
     * 创建 RAG 召回服务。
     *
     * @param dashVectorClient DashVector 客户端
     * @param embeddingService 向量化服务
     * @param ragDocumentMapper RAG 文档数据访问器
     * @param ragChunkMapper RAG chunk 数据访问器
     */
    public RagRetrievalService(DashVectorClient dashVectorClient,
                               EmbeddingService embeddingService,
                               RagDocumentMapper ragDocumentMapper,
                               RagChunkMapper ragChunkMapper) {
        this.dashVectorClient = dashVectorClient;
        this.embeddingService = embeddingService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.ragChunkMapper = ragChunkMapper;
    }

    /**
     * 执行向量召回，按配置选择 pgvector、DashVector 或 pgvector 失败后降级 DashVector。
     *
     * @param queryText 已重写的查询文本
     * @param region 区域过滤条件
     * @param sourceTypes 来源类型过滤条件
     * @param limit 召回数量上限
     * @return 向量召回结果
     */
    public List<RagSearchResult> retrieveVector(String queryText,
                                                String region,
                                                List<String> sourceTypes,
                                                int limit) {
        try {
            float[] queryVector = embeddingService.embed(queryText);
            if (isDashVectorOnly()) {
                return retrieveDashVectorFallback(queryVector, region, limit);
            }
            List<RagSearchMatch> pgVectorMatches = ragChunkMapper.searchByPgVector(
                    toPgVectorLiteral(queryVector), region, sourceTypes, limit);
            if (pgVectorMatches != null && !pgVectorMatches.isEmpty()) {
                return pgVectorMatches.stream()
                        .map(match -> toSearchResult(match, safeScore(match.getVectorScore()), 0, "pgvector"))
                        .filter(result -> !result.chunkText().isBlank())
                        .toList();
            }
            return isDashVectorFallbackEnabled()
                    ? retrieveDashVectorFallback(queryVector, region, limit)
                    : List.of();
        } catch (Exception e) {
            log.warn("RAG vector query failed, query='{}': {}", queryText, e.getMessage());
            if (isDashVectorFallbackEnabled() || isDashVectorOnly()) {
                try {
                    return retrieveDashVectorFallback(embeddingService.embed(queryText), region, limit);
                } catch (Exception fallbackError) {
                    log.warn("RAG DashVector fallback failed, query='{}': {}", queryText, fallbackError.getMessage());
                }
            }
            return List.of();
        }
    }

    /**
     * 执行关键词召回，优先使用 PostgreSQL 全文检索，失败后降级到 LIKE 召回。
     *
     * @param queryText 已重写的查询文本
     * @param region 区域过滤条件
     * @param sourceTypes 来源类型过滤条件
     * @param limit 召回数量上限
     * @return 关键词召回结果
     */
    public List<RagSearchResult> retrieveKeyword(String queryText,
                                                 String region,
                                                 List<String> sourceTypes,
                                                 int limit) {
        try {
            List<RagSearchMatch> matches = ragChunkMapper.searchByFullText(queryText, region, sourceTypes, limit);
            if (matches != null && !matches.isEmpty()) {
                return matches.stream()
                        .map(match -> toSearchResult(match, 0, safeScore(match.getBm25Score()), "postgres-fts"))
                        .filter(result -> !result.chunkText().isBlank())
                        .toList();
            }
        } catch (Exception e) {
            log.debug("PostgreSQL FTS unavailable, falling back to LIKE keyword recall: {}", e.getMessage());
        }

        try {
            List<RagSearchMatch> matches = ragChunkMapper.searchByKeywordLike(queryText, region, sourceTypes, limit);
            return matches == null ? List.of() : matches.stream()
                    .map(match -> toSearchResult(match, 0, safeScore(match.getBm25Score()), "keyword-like"))
                    .filter(result -> !result.chunkText().isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("RAG keyword query failed, query='{}': {}", queryText, e.getMessage());
            return List.of();
        }
    }

    /**
     * 将 DashVector 召回结果补齐文档元信息，形成统一的 RAG 搜索结果。
     *
     * @param queryVector 查询向量
     * @param region 区域过滤条件
     * @param limit 召回数量上限
     * @return DashVector 召回结果
     */
    private List<RagSearchResult> retrieveDashVectorFallback(float[] queryVector, String region, int limit) {
        List<DashVectorClient.DashVectorResult> results = dashVectorClient.search(queryVector, limit, region);
        List<RagSearchResult> values = new ArrayList<>();
        for (DashVectorClient.DashVectorResult result : results) {
            RagChunk chunk = ragChunkMapper.findByDashvectorId(result.id);
            RagDocument doc = chunk == null ? null : ragDocumentMapper.findById(chunk.getDocumentId());
            String chunkText = chunk != null && chunk.getChunkText() != null
                    ? chunk.getChunkText()
                    : result.fields.getOrDefault("chunkText", "");
            String sourceType = doc != null ? doc.getSourceType() : "static_knowledge";
            if (!RAG_SOURCE_TYPES.contains(normalizeSourceType(sourceType))) {
                continue;
            }
            values.add(new RagSearchResult(
                    chunk != null ? chunk.getId() : null,
                    chunk != null ? chunk.getDocumentId() : null,
                    chunkText,
                    doc != null ? doc.getTitle() : null,
                    doc != null ? doc.getRegion() : result.fields.getOrDefault("region", region),
                    normalizeSourceType(sourceType),
                    doc != null ? doc.getSourceName() : "dashvector",
                    doc != null ? doc.getSourceUrl() : null,
                    result.score,
                    0,
                    0,
                    result.score,
                    "vectorScore=" + formatScore(result.score) + "; source=dashvector",
                    chunk == null ? null : chunk.getLocatorJson(),
                    chunk == null ? null : chunk.getIndexVersion(),
                    "keyword-fallback"
            ));
        }
        return values;
    }

    /**
     * 将 mapper 返回的搜索命中转换成统一 RAG 搜索结果。
     *
     * @param match 搜索命中
     * @param vectorScore 向量分数
     * @param bm25Score 关键词分数
     * @param source 召回来源说明
     * @return RAG 搜索结果
     */
    private RagSearchResult toSearchResult(RagSearchMatch match,
                                           double vectorScore,
                                           double bm25Score,
                                           String source) {
        return new RagSearchResult(
                match.getChunkId(),
                match.getDocumentId(),
                nullToEmpty(match.getChunkText()),
                match.getTitle(),
                match.getRegion(),
                normalizeSourceType(match.getSourceType()),
                blankToDefault(match.getSourceName(), "manual"),
                match.getSourceUrl(),
                vectorScore,
                bm25Score,
                safeScore(match.getFreshnessScore()),
                0,
                "source=" + source,
                match.getLocatorJson(),
                match.getIndexVersion(),
                source.contains("fallback") ? "keyword-fallback" : null
        );
    }

    /**
     * 判断当前配置是否只使用 PostgreSQL pgvector。
     *
     * @return 使用 PostgreSQL 或 PostgreSQL 带降级时返回 true
     */
    public boolean isPostgresEnabled() {
        String backend = normalizeVectorBackend();
        return VECTOR_BACKEND_POSTGRES.equals(backend) || VECTOR_BACKEND_POSTGRES_WITH_FALLBACK.equals(backend);
    }

    /**
     * 判断当前配置是否只使用 DashVector。
     *
     * @return 只使用 DashVector 时返回 true
     */
    boolean isDashVectorOnly() {
        return VECTOR_BACKEND_DASHVECTOR.equals(normalizeVectorBackend());
    }

    /**
     * 判断写入阶段是否需要同步 DashVector。
     *
     * @return DashVector-only 或 PostgreSQL 带降级时返回 true
     */
    public boolean isDashVectorEnabled() {
        String backend = normalizeVectorBackend();
        return VECTOR_BACKEND_DASHVECTOR.equals(backend) || VECTOR_BACKEND_POSTGRES_WITH_FALLBACK.equals(backend);
    }

    /**
     * 判断 pgvector 失败后是否允许降级到 DashVector。
     *
     * @return 启用降级时返回 true
     */
    boolean isDashVectorFallbackEnabled() {
        return VECTOR_BACKEND_POSTGRES_WITH_FALLBACK.equals(normalizeVectorBackend());
    }

    /**
     * 归一化向量后端配置。
     *
     * @return 小写后端名称
     */
    String normalizeVectorBackend() {
        if (vectorBackend == null || vectorBackend.isBlank()) {
            return VECTOR_BACKEND_POSTGRES;
        }
        return vectorBackend.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 将 Java float 数组格式化为 pgvector 可接受的字面量。
     *
     * @param vector 向量数组
     * @return pgvector 字面量
     */
    private String toPgVectorLiteral(float[] vector) {
        return toJsonArray(vector);
    }

    /**
     * 将 Java float 数组格式化为 JSON 数组字符串。
     *
     * @param vector 向量数组
     * @return JSON 数组字符串
     */
    public String toJsonArray(float[] vector) {
        if (vector == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 安全读取可参与排序的分数。
     *
     * @param score 原始分数
     * @return 非空、非 NaN、非无穷大的分数
     */
    private double safeScore(Double score) {
        if (score == null || score.isNaN() || score.isInfinite()) {
            return 0.0d;
        }
        return score;
    }

    /**
     * 将空文本规整为空字符串。
     *
     * @param value 原始文本
     * @return 非 null 文本
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 将空白文本替换为默认值。
     *
     * @param value 原始文本
     * @param defaultValue 默认文本
     * @return 非空白文本
     */
    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 归一化 RAG 来源类型。
     *
     * @param sourceType 原始来源类型
     * @return 合法的来源类型
     */
    private String normalizeSourceType(String sourceType) {
        String value = sourceType == null || sourceType.isBlank()
                ? "static_knowledge"
                : sourceType.trim().toLowerCase(Locale.ROOT);
        if (!RAG_SOURCE_TYPES.contains(value)) {
            return "static_knowledge";
        }
        return value;
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
}
