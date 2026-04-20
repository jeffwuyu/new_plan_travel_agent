package com.travelagent.service.rag;

import com.travelagent.client.dashvector.DashVectorClient;
import com.travelagent.client.oss.OssClient;
import com.travelagent.mapper.RagChunkMapper;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.entity.RagChunk;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.impl.RagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，验证 RagServiceImpl 的注册、入库与查询行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagServiceImpl Tests")
class RagServiceImplTest {

    @Mock private OssClient ossClient;
    @Mock private DashVectorClient dashVectorClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private RagChunkMapper ragChunkMapper;

    @InjectMocks private RagServiceImpl ragService;

    private static final float[] DUMMY_VEC = new float[1536];
    private static final Long DOC_ID = 1L;

    @BeforeEach
    void setUp() {
        // Fill dummy vector with non-zero values
        for (int i = 0; i < DUMMY_VEC.length; i++) DUMMY_VEC[i] = 0.01f * (i % 100);
    }

    // -----------------------------------------------------------------------
    // registerDocument
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("registerDocument: inserts doc with status=pending and returns it")
    void registerDocument_insertsPending() {
        doAnswer(inv -> {
            RagDocument doc = inv.getArgument(0);
            doc.setId(DOC_ID);
            return 1;
        }).when(ragDocumentMapper).insert(any(RagDocument.class));

        RagDocument doc = ragService.registerDocument("rag/docs/test.txt", "Test Guide", "西安市", "text");

        assertThat(doc.getId()).isEqualTo(DOC_ID);
        assertThat(doc.getStatus()).isEqualTo("pending");
        assertThat(doc.getOssKey()).isEqualTo("rag/docs/test.txt");
    }

    // -----------------------------------------------------------------------
    // ingestDocument
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ingestDocument: splits text into chunks, embeds, upserts, saves chunks, marks indexed")
    void ingestDocument_fullPipeline() {
        // 1000 chars → at least 2 chunks (CHUNK_SIZE=768, OVERLAP=96)
        String text = "西安".repeat(500);
        byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/test.txt");
        doc.setRegion("西安市");
        doc.setDocType("text");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);
        when(ossClient.downloadDocument("rag/docs/test.txt")).thenReturn(textBytes);
        when(embeddingService.embedBatch(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> DUMMY_VEC.clone()).toList();
        });

        ragService.ingestDocument(DOC_ID);

        // Verify embedding was called once (all chunks in one batch for small text)
        verify(embeddingService, atLeastOnce()).embedBatch(any());
        // Verify each chunk was upserted
        verify(dashVectorClient, atLeastOnce()).upsert(anyString(), any(float[].class), anyMap());
        // Verify chunk rows saved to DB
        verify(ragChunkMapper, atLeastOnce()).insertBatch(any());
        // Verify final status update
        verify(ragDocumentMapper).updateStatus(DOC_ID, "indexed", null);
    }

    @Test
    @DisplayName("ingestDocument: marks document as failed when OSS download throws")
    void ingestDocument_marksFailedOnOssError() {
        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/missing.txt");
        doc.setRegion("北京市");
        doc.setDocType("text");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);
        when(ossClient.downloadDocument(any())).thenThrow(new RuntimeException("OSS error"));

        ragService.ingestDocument(DOC_ID);

        verify(ragDocumentMapper).updateStatus(eq(DOC_ID), eq("failed"), anyString());
        verify(embeddingService, never()).embedBatch(any());
    }

    @Test
    @DisplayName("ingestDocument: does nothing when document not found")
    void ingestDocument_noopWhenDocumentNotFound() {
        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(null);

        ragService.ingestDocument(DOC_ID);

        verify(ossClient, never()).downloadDocument(any());
    }

    // -----------------------------------------------------------------------
    // queryChunks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("queryChunks: returns chunk texts from DashVector results")
    void queryChunks_returnsChunkTexts() {
        when(embeddingService.embed("西安美食")).thenReturn(DUMMY_VEC);
        DashVectorClient.DashVectorResult r1 = new DashVectorClient.DashVectorResult("id1", 0.95f,
                Map.of("region", "西安市", "chunkText", "兵马俑简介"));
        DashVectorClient.DashVectorResult r2 = new DashVectorClient.DashVectorResult("id2", 0.88f,
                Map.of("region", "西安市", "chunkText", "回民街美食推荐"));
        when(dashVectorClient.search(any(), eq(3), eq("西安市"))).thenReturn(List.of(r1, r2));

        List<String> chunks = ragService.queryChunks("西安美食", "西安市", 3);

        assertThat(chunks).containsExactly("兵马俑简介", "回民街美食推荐");
    }

    @Test
    @DisplayName("queryChunks: returns empty list when DashVector throws")
    void queryChunks_returnsEmptyOnError() {
        when(embeddingService.embed(any())).thenReturn(DUMMY_VEC);
        when(dashVectorClient.search(any(), anyInt(), any())).thenThrow(new RuntimeException("network error"));

        List<String> chunks = ragService.queryChunks("test query", "西安市", 5);

        assertThat(chunks).isEmpty();
    }

    // -----------------------------------------------------------------------
    // splitIntoChunks (tested via ReflectionTestUtils to preserve encapsulation)
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<String> splitIntoChunks(String text) {
        return (List<String>) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(ragService, "splitIntoChunks", text);
    }

    @Test
    @DisplayName("splitIntoChunks: 1000-char text produces at least 2 chunks")
    void splitIntoChunks_multipleChunksForLongText() {
        String longText = "A".repeat(1000);
        List<String> chunks = splitIntoChunks(longText);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("splitIntoChunks: short text produces single chunk")
    void splitIntoChunks_singleChunkForShortText() {
        String shortText = "Short text.";
        List<String> chunks = splitIntoChunks(shortText);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(shortText);
    }

    @Test
    @DisplayName("splitIntoChunks: blank text returns empty list")
    void splitIntoChunks_emptyOnBlankInput() {
        assertThat(splitIntoChunks("   ")).isEmpty();
    }
}
