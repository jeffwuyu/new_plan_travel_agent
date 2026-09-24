package com.travelagent.service.rag;

import com.travelagent.model.entity.RagDocument;

import java.util.List;

/**
 * RAG 服务接口，提供知识文档注册、入库生命周期管理和混合检索入口。
 */
public interface RagService {

    /**
     * 注册静态知识文档元数据，初始状态为 pending；入库任务需单独触发。
     *
     * @param ossKey OSS 对象 key
     * @param title 文档标题
     * @param region 区域标签，用于检索过滤
     * @param docType 文档类型，例如 pdf、markdown、text
     * @return 已持久化并带主键的文档实体
     */
    RagDocument registerDocument(String ossKey, String title, String region, String docType);

    /**
     * 注册带来源分类的 RAG 文档元数据。
     *
     * <p>只允许 {@code static_knowledge} 和 {@code user_preference}。票务、天气、交通等实时事实
     * 必须保留在工具调用结果中，不能混入 RAG 知识库。</p>
     *
     * @param ossKey OSS 对象 key
     * @param title 文档标题
     * @param region 区域标签
     * @param docType 文档类型
     * @param sourceType 来源类型
     * @param sourceName 来源名称
     * @param sourceUrl 来源 URL
     * @param metadataJson 额外元数据 JSON
     * @return 已持久化的文档实体
     */
    RagDocument registerDocument(String ossKey,
                                 String title,
                                 String region,
                                 String docType,
                                 String sourceType,
                                 String sourceName,
                                 String sourceUrl,
                                 String metadataJson);

    /**
     * 异步执行文档入库：下载 OSS 原文、提取文本、分块、向量化、写入 chunk，并更新文档生命周期。
     *
     * @param documentId rag_documents 主键
     */
    void ingestDocument(Long documentId);

    /**
     * 清理旧 chunk 和外部向量后重新入库。
     *
     * @param documentId 文档 ID
     */
    void retryIngest(Long documentId);

    /**
     * 禁用文档，使其退出检索范围并等待管理端复核。
     *
     * @param documentId 文档 ID
     */
    void disableDocument(Long documentId);

    /**
     * 重新启用禁用文档，并将其放回待入库状态。
     *
     * @param documentId 文档 ID
     */
    void reenableDocument(Long documentId);

    /**
     * 删除源文档、chunk 和外部向量索引。
     *
     * @param documentId 文档 ID
     */
    void deleteDocument(Long documentId);

    /**
     * 查询与用户问题最相关的 chunk 文本。
     *
     * <p>底层通过混合检索召回并融合排序；检索后端不可用时返回空列表，不向上抛出异常。</p>
     *
     * @param queryText 查询文本
     * @param region 区域过滤，可为空
     * @param topK 返回数量上限
     * @return chunk 文本列表
     */
    List<String> queryChunks(String queryText, String region, int topK);

    /**
     * 查询带来源、向量分、关键词分和融合原因的排序结果。
     *
     * @param queryText 查询文本
     * @param region 区域过滤，可为空
     * @param topK 返回数量上限
     * @return 排序后的 RAG 搜索结果
     */
    List<RagSearchResult> hybridSearch(String queryText, String region, int topK);
}
