package com.travelagent.service.rag;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.travelagent.config.LlmConfig;
import com.travelagent.service.rag.impl.EmbeddingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，验证 EmbeddingService 的向量化行为与批量分批逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingService Tests")
class EmbeddingServiceTest {

    @Mock private TextEmbedding textEmbedding;
    @Mock private LlmConfig llmConfig;

    @InjectMocks private EmbeddingServiceImpl embeddingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(embeddingService, "embeddingModel", "text-embedding-v3");
        lenient().when(llmConfig.getDashscopeApiKey()).thenReturn("test-api-key");
    }

    /**
     * Builds a TextEmbeddingResult with {@code count} embeddings, reusing a single
     * item mock to keep memory low. Each embedding has {@code dim} dimensions.
     */
    private TextEmbeddingResult buildResult(int count, int dim) {
        List<Double> vec = new ArrayList<>(dim);
        for (int d = 0; d < dim; d++) vec.add(d * 0.001);

        // Reuse ONE item mock for all entries — saves heap vs. 25 separate mocks
        TextEmbeddingResultItem sharedItem = mock(TextEmbeddingResultItem.class);
        lenient().when(sharedItem.getEmbedding()).thenReturn(vec);

        List<TextEmbeddingResultItem> items = Collections.nCopies(count, sharedItem);

        TextEmbeddingOutput output = mock(TextEmbeddingOutput.class);
        lenient().when(output.getEmbeddings()).thenReturn(items);

        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        lenient().when(result.getOutput()).thenReturn(output);
        return result;
    }

    @Test
    @DisplayName("embed: returns float array of correct dimension (1536)")
    void embed_returnsCorrectDimension() throws Exception {
        doReturn(buildResult(1, 1536)).when(textEmbedding).call(any());

        float[] vec = embeddingService.embed("西安美食攻略");

        assertThat(vec).hasSize(1536);
    }

    @Test
    @DisplayName("embedBatch: 30 texts triggers 2 SDK calls (batch size 25)")
    void embedBatch_splitIntoTwoBatches() throws Exception {
        TextEmbeddingResult firstBatch  = buildResult(25, 4);
        TextEmbeddingResult secondBatch = buildResult(5, 4);
        doReturn(firstBatch).doReturn(secondBatch).when(textEmbedding).call(any());

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 30; i++) texts.add("text " + i);

        List<float[]> results = embeddingService.embedBatch(texts);

        assertThat(results).hasSize(30);
        verify(textEmbedding, times(2)).call(any());
    }

    @Test
    @DisplayName("embedBatch: SDK exception is wrapped as RuntimeException")
    void embedBatch_sdkExceptionWrapped() throws Exception {
        doThrow(new RuntimeException("SDK error")).when(textEmbedding).call(any());

        assertThatThrownBy(() -> embeddingService.embedBatch(List.of("test")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Embedding batch");
    }
}
