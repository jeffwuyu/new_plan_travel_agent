package com.travelagent.service.rag;

import java.util.List;

/**
 * 中文注释：服务接口，将文本转换为向量表示，用于 RAG 入库和查询。
 */
public interface EmbeddingService {

    /**
     * Converts a single text into a 1536-dimensional float vector
     * using Dashscope text-embedding-v3.
     *
     * @param text input text (≤512 tokens recommended)
     * @return float array of length 1536
     */
    float[] embed(String text);

    /**
     * Converts a list of texts into vectors in batches of 25 (Dashscope API limit).
     *
     * @param texts list of input texts
     * @return list of float arrays, same order as input
     */
    List<float[]> embedBatch(List<String> texts);
}
