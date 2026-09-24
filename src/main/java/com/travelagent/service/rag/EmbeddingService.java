package com.travelagent.service.rag;

import java.util.List;

/**
 * 向量化服务接口，将文本转换为 embedding，用于 RAG 入库和查询召回。
 */
public interface EmbeddingService {

    /**
     * 将单段文本转换为向量。
     *
     * @param text 输入文本
     * @return embedding 向量
     */
    float[] embed(String text);

    /**
     * 批量将文本转换为向量，返回顺序与输入顺序一致。
     *
     * @param texts 输入文本列表
     * @return embedding 向量列表
     */
    List<float[]> embedBatch(List<String> texts);
}
