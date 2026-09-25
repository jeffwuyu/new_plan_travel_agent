package com.travelagent.service.rag.impl;

import com.travelagent.client.dashvector.DashVectorClient;
import com.travelagent.client.oss.OssClient;
import com.travelagent.mapper.RagChunkMapper;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.entity.RagChunk;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.EmbeddingService;
import com.travelagent.service.rag.RagService;
import com.travelagent.service.rag.RagSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * RAG 服务实现，协调文档注册、异步入库、生命周期管理和混合检索入口。
 *
 * <p>文本分块、结果融合和召回后端选择分别委托给专门协作者，服务本体只保留文档生命周期编排。</p>
 */
@Service
public class RagServiceImpl implements RagService {

    private static final String CHUNK_POLICY_VERSION = "paragraph-v1";

    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);

    private static final int FIELD_PREVIEW_LEN = 200;

    private static final Set<String> RAG_SOURCE_TYPES = Set.of("static_knowledge", "user_preference");
    private static final List<String> DEFAULT_RAG_SOURCE_TYPES = List.of("static_knowledge", "user_preference");
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_INDEXING = "indexing";
    private static final String STATUS_INDEXED = "indexed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_DISABLED = "disabled";
    private static final String ERROR_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
    private static final String ERROR_EXTRACT_FAILED = "TEXT_EXTRACT_FAILED";
    private static final String ERROR_EMBED_FAILED = "EMBEDDING_FAILED";
    private static final String ERROR_PERSIST_FAILED = "CHUNK_PERSIST_FAILED";

    @Autowired private OssClient ossClient;
    @Autowired private DashVectorClient dashVectorClient;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private RagDocumentMapper ragDocumentMapper;
    @Autowired private RagChunkMapper ragChunkMapper;
    @Autowired private PdfTextExtractor pdfTextExtractor;
    @Autowired private PlainTextExtractor plainTextExtractor;
    @Autowired private RagTextChunker ragTextChunker;
    @Autowired private RagResultFusionService ragResultFusionService;
    @Autowired private RagRetrievalService ragRetrievalService;

    /**
     * 获取文本分块器，兼容单元测试中手工构造服务对象的场景。
     *
     * @return 文本分块器
     */
    private RagTextChunker chunker() {
        if (ragTextChunker == null) {
            ragTextChunker = new RagTextChunker();
        }
        return ragTextChunker;
    }

    /**
     * 获取结果融合服务，兼容单元测试中手工构造服务对象的场景。
     *
     * @return 结果融合服务
     */
    private RagResultFusionService fusionService() {
        if (ragResultFusionService == null) {
            ragResultFusionService = new RagResultFusionService();
        }
        return ragResultFusionService;
    }

    /**
     * 获取召回服务，兼容单元测试中手工构造服务对象的场景。
     *
     * @return RAG 召回服务
     */
    private RagRetrievalService retrievalService() {
        if (ragRetrievalService == null) {
            ragRetrievalService = new RagRetrievalService(
                    dashVectorClient, embeddingService, ragDocumentMapper, ragChunkMapper);
        }
        return ragRetrievalService;
    }

    // -----------------------------------------------------------------------
    // Register
    // -----------------------------------------------------------------------

    /**
     * 注册默认静态知识文档元数据，入库任务由调用方后续触发。
     *
     * @param ossKey OSS 对象 key
     * @param title 文档标题
     * @param region 区域标签
     * @param docType 文档类型
     * @return 已持久化的文档实体
     */
    @Override
    public RagDocument registerDocument(String ossKey, String title, String region, String docType) {
        return registerDocument(ossKey, title, region, docType,
                "static_knowledge", "manual", null, null);
    }

    /**
     * 注册带来源分类的 RAG 文档元数据。
     *
     * @param ossKey OSS 对象 key
     * @param title 文档标题
     * @param region 区域标签
     * @param docType 文档类型
     * @param sourceType 来源类型
     * @param sourceName 来源名称
     * @param sourceUrl 来源 URL
     * @param metadataJson 元数据 JSON
     * @return 已持久化的文档实体
     */
    @Override
    public RagDocument registerDocument(String ossKey,
                                        String title,
                                        String region,
                                        String docType,
                                        String sourceType,
                                        String sourceName,
                                        String sourceUrl,
                                        String metadataJson) {
        RagDocument doc = new RagDocument();
        doc.setOssKey(ossKey);
        doc.setTitle(title);
        doc.setRegion(region);
        doc.setDocType(docType);
        doc.setSourceType(normalizeSourceType(sourceType));
        doc.setSourceName(sourceName == null || sourceName.isBlank() ? "manual" : sourceName.trim());
        doc.setSourceUrl(blankToNull(sourceUrl));
        doc.setMetadataJson(blankToNull(metadataJson));
        doc.setStatus(STATUS_PENDING);
        doc.setIngestProgress(0);
        doc.setRetryCount(0);
        doc.setSourceVersion(1);
        doc.setChunkPolicyVersion(CHUNK_POLICY_VERSION);
        doc.setIndexPublished(false);
        ragDocumentMapper.insert(doc);
        log.info("Registered RAG document id={}, ossKey={}", doc.getId(), ossKey);
        return doc;
    }

    // -----------------------------------------------------------------------
    // Ingest (async)
    // -----------------------------------------------------------------------

    /**
     * 异步执行文档入库流程，并在任一阶段失败时写入失败状态和错误码。
     *
     * @param documentId 文档 ID
     */
    @Override
    @Async("ragIngestionExecutor")
    public void ingestDocument(Long documentId) {
        log.info("Starting ingestion for documentId={}", documentId);
        RagDocument doc = ragDocumentMapper.findById(documentId);
        if (doc == null) {
            log.error("Document not found: id={}", documentId);
            return;
        }
        if (STATUS_DISABLED.equals(doc.getStatus())) {
            log.info("Skipping ingestion for disabled documentId={}", documentId);
            return;
        }

        try {
            ragDocumentMapper.updateLifecycle(documentId, STATUS_INDEXING, 5,
                    safeRetryCount(doc), null, null);

            // 从 OSS 下载源文档字节。
            byte[] rawBytes = ossClient.downloadDocument(doc.getOssKey());
            ragDocumentMapper.updateLifecycle(documentId, STATUS_INDEXING, 20,
                    safeRetryCount(doc), null, null);

            // 按文档类型提取纯文本。
            String text = extractText(rawBytes, doc.getDocType());
            doc.setContentHash(sha256(rawBytes));
            doc.setIndexVersion(doc.getId() + "-v" + safeSourceVersion(doc));
            doc.setIndexPublished(false);
            ragDocumentMapper.updateLifecycle(documentId, STATUS_INDEXING, 40,
                    safeRetryCount(doc), null, null);

            // 按段落和窗口策略切分 chunk。
            List<String> chunks = splitIntoChunks(text);
            log.info("Document id={} split into {} chunks", documentId, chunks.size());
            ragDocumentMapper.updateLifecycle(documentId, STATUS_INDEXING, 55,
                    safeRetryCount(doc), null, null);

            // 批量向量化所有 chunk。
            List<float[]> vectors = embeddingService.embedBatch(chunks);
            ragDocumentMapper.updateLifecycle(documentId, STATUS_INDEXING, 75,
                    safeRetryCount(doc), null, null);

            // 默认写入 PostgreSQL pgvector；仅在显式配置时同步 DashVector。
            List<RagChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                float[] vector = vectors.get(i);
                String dashvectorId = "doc" + documentId + "-chunk" + i;

                Map<String, String> fields = new HashMap<>();
                fields.put("region", doc.getRegion() != null ? doc.getRegion() : "");
                fields.put("chunkText", chunkText.length() > FIELD_PREVIEW_LEN
                        ? chunkText.substring(0, FIELD_PREVIEW_LEN)
                        : chunkText);

                if (retrievalService().isDashVectorEnabled()) {
                    dashVectorClient.upsert(dashvectorId, vector, fields);
                }

                RagChunk chunk = new RagChunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setStableChunkId(documentId + ":" + safeSourceVersion(doc) + ":" + i + ":" + sha256(chunkText.getBytes(StandardCharsets.UTF_8)).substring(0, 16));
                chunk.setChunkPolicyVersion(CHUNK_POLICY_VERSION);
                chunk.setIndexVersion(doc.getIndexVersion());
                chunk.setSourceStart(text.indexOf(chunkText));
                chunk.setSourceEnd(chunk.getSourceStart() < 0 ? null : chunk.getSourceStart() + chunkText.length());
                chunk.setLocatorJson("{\"chunkIndex\":" + i + "}");
                chunk.setChunkText(chunkText);
                chunk.setDashvectorId(dashvectorId);
                String vectorLiteral = retrievalService().toJsonArray(vector);
                chunk.setEmbeddingVector(vectorLiteral);
                chunk.setEmbeddingJson(vectorLiteral);
                chunk.setSearchText(buildSearchText(doc, chunkText));
                chunk.setMetadataJson(doc.getMetadataJson());
                chunk.setTokenCount(estimateTokens(chunkText));
                chunkEntities.add(chunk);
            }

            if (!chunkEntities.isEmpty()) {
                if (retrievalService().isPostgresEnabled()) {
                    ragChunkMapper.insertBatchPostgres(chunkEntities);
                } else {
                    ragChunkMapper.insertBatch(chunkEntities);
                }
            }

            // 全部 chunk 写入成功后标记文档可检索。
            ragDocumentMapper.updateLifecycle(documentId, STATUS_INDEXED, 100,
                    safeRetryCount(doc), null, null);
            ragDocumentMapper.publishIndex(documentId, doc.getIndexVersion(), doc.getContentHash());
            log.info("Ingestion complete for documentId={}, chunks={}", documentId, chunks.size());

        } catch (Exception e) {
            log.error("Ingestion failed for documentId={}: {}", documentId, e.getMessage(), e);
            ragDocumentMapper.updateLifecycle(documentId, STATUS_FAILED, 0,
                    safeRetryCount(doc), classifyIngestErrorCode(e), e.getMessage());
        }
    }

    private int safeSourceVersion(RagDocument doc) {
        return doc.getSourceVersion() == null ? 1 : doc.getSourceVersion();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    @Override
    public void retryIngest(Long documentId) {
        RagDocument doc = requireDocument(documentId);
        if (STATUS_DISABLED.equals(doc.getStatus())) {
            throw new IllegalStateException("Disabled RAG document must be re-enabled before retry");
        }
        deleteExternalArtifacts(doc);
        ragChunkMapper.deleteByDocumentId(documentId);
        ragDocumentMapper.incrementRetryCount(documentId);
        RagDocument refreshed = requireDocument(documentId);
        ragDocumentMapper.updateLifecycle(documentId, STATUS_PENDING, 0,
                safeRetryCount(refreshed), null, null);
        ingestDocument(documentId);
    }

    @Override
    public void disableDocument(Long documentId) {
        requireDocument(documentId);
        ragDocumentMapper.disableDocument(documentId);
    }

    @Override
    public void reenableDocument(Long documentId) {
        requireDocument(documentId);
        ragDocumentMapper.reenableDocument(documentId);
    }

    @Override
    public void deleteDocument(Long documentId) {
        RagDocument doc = requireDocument(documentId);
        deleteExternalArtifacts(doc);
        ragChunkMapper.deleteByDocumentId(documentId);
        try {
            ossClient.deleteDocument(doc.getOssKey());
        } catch (Exception e) {
            log.warn("Failed to delete OSS object for documentId={}, ossKey={}: {}",
                    documentId, doc.getOssKey(), e.getMessage());
        }
        ragDocumentMapper.deleteById(documentId);
    }

    // -----------------------------------------------------------------------
    // Query
    // -----------------------------------------------------------------------

    /**
     * 查询最相关的 chunk 文本列表。
     *
     * @param queryText 查询文本
     * @param region 区域过滤条件
     * @param topK 返回数量上限
     * @return chunk 文本列表
     */
    @Override
    public List<String> queryChunks(String queryText, String region, int topK) {
        return hybridSearch(queryText, region, topK).stream()
                .map(RagSearchResult::chunkText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 执行 RAG 混合检索，先向量和关键词召回，再进行融合排序。
     *
     * @param queryText 查询文本
     * @param region 区域过滤条件
     * @param topK 返回数量上限
     * @return 融合排序后的检索结果
     */
    @Override
    public List<RagSearchResult> hybridSearch(String queryText, String region, int topK) {
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        String rewrittenQuery = rewriteQuery(queryText, region);
        int recallK = Math.max(topK * 4, topK);
        List<String> sourceTypes = DEFAULT_RAG_SOURCE_TYPES;

        List<RagSearchResult> vectorResults = retrievalService()
                .retrieveVector(rewrittenQuery, region, sourceTypes, recallK);
        List<RagSearchResult> keywordResults = retrievalService()
                .retrieveKeyword(rewrittenQuery, region, sourceTypes, recallK);
        return fusionService().fuseResults(vectorResults, keywordResults, topK);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * 根据文档类型选择文本提取器。
     *
     * @param rawBytes 原始文档字节
     * @param docType 文档类型
     * @return 提取出的纯文本
     */
    private String extractText(byte[] rawBytes, String docType) {
        if ("pdf".equalsIgnoreCase(docType)) {
            return pdfTextExtractor.extract(rawBytes);
        }
        return plainTextExtractor.extract(rawBytes);
    }

    /**
     * 将文档文本切分为 chunk。
     *
     * <p>该方法保留在服务内作为兼容入口，实际分块逻辑已下沉到 `RagTextChunker`。</p>
     *
     * @param text 待切分文本
     * @return chunk 文本列表
     */
    List<String> splitIntoChunks(String text) {
        return chunker().splitIntoChunks(text);
    }

    /**
     * 粗略估算 chunk token 数，用于后续管理统计和成本分析。
     *
     * @param text 文本内容
     * @return 估算 token 数
     */
    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 1.5);
    }

    private String rewriteQuery(String queryText, String region) {
        String query = queryText == null ? "" : queryText.trim();
        if (region == null || region.isBlank() || query.contains(region.trim())) {
            return query;
        }
        return region.trim() + " " + query;
    }

    private String buildSearchText(RagDocument doc, String chunkText) {
        List<String> parts = new ArrayList<>();
        parts.add(doc.getTitle());
        parts.add(doc.getRegion());
        parts.add(doc.getSourceName());
        parts.add(chunkText);
        return parts.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    private String normalizeSourceType(String sourceType) {
        String value = sourceType == null || sourceType.isBlank()
                ? "static_knowledge"
                : sourceType.trim().toLowerCase(Locale.ROOT);
        if ("realtime".equals(value) || "real_time".equals(value) || "realtime_tool".equals(value)) {
            throw new IllegalArgumentException("实时信息必须通过 Web/Tool 获取，不能写入 RAG 知识库");
        }
        if (!RAG_SOURCE_TYPES.contains(value)) {
            throw new IllegalArgumentException("sourceType 必须为 static_knowledge 或 user_preference");
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int safeRetryCount(RagDocument doc) {
        return doc.getRetryCount() == null ? 0 : Math.max(0, doc.getRetryCount());
    }

    private String classifyIngestErrorCode(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("oss") || message.contains("download")) {
            return ERROR_DOWNLOAD_FAILED;
        }
        if (message.contains("pdf") || message.contains("extract") || message.contains("parse")) {
            return ERROR_EXTRACT_FAILED;
        }
        if (message.contains("embed")) {
            return ERROR_EMBED_FAILED;
        }
        return ERROR_PERSIST_FAILED;
    }

    private RagDocument requireDocument(Long documentId) {
        RagDocument doc = ragDocumentMapper.findById(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("RAG document not found: " + documentId);
        }
        return doc;
    }

    private void deleteExternalArtifacts(RagDocument doc) {
        List<RagChunk> chunks = ragChunkMapper.findByDocumentId(doc.getId());
        List<String> dashvectorIds = chunks.stream()
                .map(RagChunk::getDashvectorId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (!dashvectorIds.isEmpty()) {
            try {
                dashVectorClient.delete(dashvectorIds);
            } catch (Exception e) {
                log.warn("Failed to delete DashVector docs for documentId={}: {}", doc.getId(), e.getMessage());
            }
        }
    }
}
