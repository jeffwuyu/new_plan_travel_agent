package com.travelagent.service.rag.impl;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.travelagent.config.LlmConfig;
import com.travelagent.service.rag.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文注释：服务实现类，调用 Dashscope text-embedding-v3 将文本转换为 1536 维向量。
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);
    private static final int BATCH_SIZE = 25;

    @Autowired
    private TextEmbedding textEmbedding;

    @Autowired
    private LlmConfig llmConfig;

    @Value("${dashscope.embedding.model:text-embedding-v3}")
    private String embeddingModel;

    /**
     * 处理embed。
     * @param text 文本内容
     * @return 返回处理结果。
     */
    @Override
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    /**
     * 处理embedBatch。
     * @param texts t ex ts 参数
     * @return 返回处理后的列表结果。
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> allVectors = new ArrayList<>(texts.size());
        int total = texts.size();

        for (int start = 0; start < total; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, total);
            List<String> batch = texts.subList(start, end);

            try {
                TextEmbeddingParam param = TextEmbeddingParam.builder()
                        .apiKey(llmConfig.getDashscopeApiKey())
                        .model(embeddingModel)
                        .texts(batch)
                        .build();

                TextEmbeddingResult result = textEmbedding.call(param);
                result.getOutput().getEmbeddings().forEach(emb -> {
                    List<Double> doubleVec = emb.getEmbedding();
                    float[] floatVec = new float[doubleVec.size()];
                    for (int i = 0; i < doubleVec.size(); i++) {
                        floatVec[i] = doubleVec.get(i).floatValue();
                    }
                    allVectors.add(floatVec);
                });
                log.debug("Embedded batch [{}-{}] size={}", start, end - 1, end - start);
            } catch (Exception e) {
                throw new RuntimeException("Embedding batch [" + start + "," + end + ") failed", e);
            }
        }
        return allVectors;
    }
}
