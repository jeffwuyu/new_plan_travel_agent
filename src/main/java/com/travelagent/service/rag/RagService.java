package com.travelagent.service.rag;

import com.travelagent.model.entity.RagDocument;

import java.util.List;

/**
 * 中文注释：服务接口，协调 RAG 文档的注册、入库流程与向量检索查询。
 */
public interface RagService {

    /**
     * Registers document metadata in MySQL with status=pending.
     * Does NOT trigger ingestion — call {@link #ingestDocument} separately.
     *
     * @param ossKey   full OSS object key (e.g. "rag/documents/xian-guide.txt")
     * @param title    human-readable document title
     * @param region   region tag (e.g. "西安市") used for vector filter during retrieval
     * @param docType  "pdf" | "markdown" | "text"
     * @return persisted {@link RagDocument} with generated id
     */
    RagDocument registerDocument(String ossKey, String title, String region, String docType);

    /**
     * Ingestion pipeline (meant to run asynchronously):
     * <ol>
     *   <li>Download raw bytes from OSS</li>
     *   <li>Extract plain text from bytes</li>
     *   <li>Split into overlapping chunks (768 chars / 96 overlap)</li>
     *   <li>Embed each chunk via {@link EmbeddingService}</li>
     *   <li>Upsert vectors to DashVector</li>
     *   <li>Persist {@link com.travelagent.model.entity.RagChunk} rows to MySQL</li>
     *   <li>Update document status to "indexed"</li>
     * </ol>
     * On any error the document status is set to "failed".
     *
     * @param documentId primary key from {@code rag_documents}
     */
    void ingestDocument(Long documentId);

    /**
     * Retrieves the most relevant chunk texts for a query.
     *
     * <p>Steps: embed queryText → DashVector ANN search (with optional region filter) →
     * return chunk texts ordered by descending similarity score.
     *
     * <p>Returns an empty list (never throws) when the vector store is unavailable.
     *
     * @param queryText free-text query (e.g. user intent or preference keywords)
     * @param region    region filter — may be null to search across all regions
     * @param topK      maximum number of chunk texts to return
     * @return list of chunk texts (may be empty)
     */
    List<String> queryChunks(String queryText, String region, int topK);
}
