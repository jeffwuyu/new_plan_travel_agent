package com.travelagent.service.rag.impl;

import com.travelagent.client.dashvector.DashVectorClient;
import com.travelagent.client.oss.OssClient;
import com.travelagent.mapper.RagChunkMapper;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.entity.RagChunk;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.EmbeddingService;
import com.travelagent.service.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 中文注释：服务实现类，协调 OSS 下载、文本分块、向量化、DashVector 入库与查询。
 */
@Service
public class RagServiceImpl implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);

    private static final int CHUNK_SIZE = 768;

    private static final int CHUNK_OVERLAP = 96;

    private static final int FIELD_PREVIEW_LEN = 200;

    @Autowired private OssClient ossClient;
    @Autowired private DashVectorClient dashVectorClient;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private RagDocumentMapper ragDocumentMapper;
    @Autowired private RagChunkMapper ragChunkMapper;
    @Autowired private PdfTextExtractor pdfTextExtractor;
    @Autowired private PlainTextExtractor plainTextExtractor;

    // -----------------------------------------------------------------------
    // Register
    // -----------------------------------------------------------------------

    /**
     * 注册document。
     * @param ossKey o ss Ke y 参数
     * @param title t it le 参数
     * @param region 区域信息
     * @param docType d oc Ty pe 参数
     * @return 返回处理结果。
     */
    @Override
    public RagDocument registerDocument(String ossKey, String title, String region, String docType) {
        RagDocument doc = new RagDocument();
        doc.setOssKey(ossKey);
        doc.setTitle(title);
        doc.setRegion(region);
        doc.setDocType(docType);
        doc.setStatus("pending");
        ragDocumentMapper.insert(doc);
        log.info("Registered RAG document id={}, ossKey={}", doc.getId(), ossKey);
        return doc;
    }

    // -----------------------------------------------------------------------
    // Ingest (async)
    // -----------------------------------------------------------------------

    /**
     * 处理ingestDocument。
     * @param documentId d oc um en tI d 参数
     */
    @Override
    @Async("agentTaskExecutor")
    public void ingestDocument(Long documentId) {
        log.info("Starting ingestion for documentId={}", documentId);
        RagDocument doc = ragDocumentMapper.findById(documentId);
        if (doc == null) {
            log.error("Document not found: id={}", documentId);
            return;
        }

        try {
            // 1. Download from OSS
            byte[] rawBytes = ossClient.downloadDocument(doc.getOssKey());

            // 2. Extract text
            String text = extractText(rawBytes, doc.getDocType());

            // 3. Split into chunks
            List<String> chunks = splitIntoChunks(text);
            log.info("Document id={} split into {} chunks", documentId, chunks.size());

            // 4. Embed all chunks in batch
            List<float[]> vectors = embeddingService.embedBatch(chunks);

            // 5. Upsert to DashVector + persist RagChunk rows
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

                dashVectorClient.upsert(dashvectorId, vector, fields);

                RagChunk chunk = new RagChunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setChunkText(chunkText);
                chunk.setDashvectorId(dashvectorId);
                chunk.setTokenCount(estimateTokens(chunkText));
                chunkEntities.add(chunk);
            }

            if (!chunkEntities.isEmpty()) {
                ragChunkMapper.insertBatch(chunkEntities);
            }

            // 6. Mark as indexed
            ragDocumentMapper.updateStatus(documentId, "indexed", null);
            log.info("Ingestion complete for documentId={}, chunks={}", documentId, chunks.size());

        } catch (Exception e) {
            log.error("Ingestion failed for documentId={}: {}", documentId, e.getMessage(), e);
            ragDocumentMapper.updateStatus(documentId, "failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Query
    // -----------------------------------------------------------------------

    /**
     * 处理queryChunks。
     * @param queryText q ue ry Te xt 参数
     * @param region 区域信息
     * @param topK t op K 参数
     * @return 返回处理后的列表结果。
     */
    @Override
    public List<String> queryChunks(String queryText, String region, int topK) {
        try {
            float[] queryVector = embeddingService.embed(queryText);
            List<DashVectorClient.DashVectorResult> results =
                    dashVectorClient.search(queryVector, topK, region);
            return results.stream()
                    .map(r -> r.fields.getOrDefault("chunkText", ""))
                    .filter(t -> !t.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("RAG query failed (returning empty), query='{}': {}", queryText, e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * 提取text。
     * @param rawBytes r aw By te s 参数
     * @param docType d oc Ty pe 参数
     * @return 返回处理结果。
     */
    private String extractText(byte[] rawBytes, String docType) {
        if ("pdf".equalsIgnoreCase(docType)) {
            return pdfTextExtractor.extract(rawBytes);
        }
        return plainTextExtractor.extract(rawBytes);
    }

    /**
     * Splits text into overlapping chunks of CHUNK_SIZE characters with CHUNK_OVERLAP overlap.
     * Splits prefer word boundaries when possible (looks back up to 30 chars for a space).
     */
    List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + CHUNK_SIZE, len);
            boolean isLastChunk = (end == len);

            // Try to break at a word boundary (only when not at end of text)
            if (!isLastChunk) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start + CHUNK_SIZE - 30) {
                    end = boundary;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (isLastChunk) break;

            start = end - CHUNK_OVERLAP;
            if (start >= end) break; // guard against degenerate overlap
        }
        return chunks;
    }

    /**
     * 处理estimateTokens。
     * @param text 文本内容
     * @return 返回处理结果。
     */
    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 1.5);
    }
}
