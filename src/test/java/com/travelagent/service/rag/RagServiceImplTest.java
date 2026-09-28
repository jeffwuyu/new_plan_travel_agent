package com.travelagent.service.rag;

import com.travelagent.client.dashvector.DashVectorClient;
import com.travelagent.client.oss.OssClient;
import com.travelagent.mapper.RagChunkMapper;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.entity.RagChunk;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.impl.PdfTextExtractor;
import com.travelagent.service.rag.impl.PlainTextExtractor;
import com.travelagent.service.rag.impl.RagRetrievalService;
import com.travelagent.service.rag.impl.RagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagServiceImpl Tests")
class RagServiceImplTest {

    @Mock private OssClient ossClient;
    @Mock private DashVectorClient dashVectorClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private RagDocumentMapper ragDocumentMapper;
    @Mock private RagChunkMapper ragChunkMapper;
    @Mock private PdfTextExtractor pdfTextExtractor;
    @Mock private PlainTextExtractor plainTextExtractor;

    @InjectMocks private RagRetrievalService ragRetrievalService;
    @InjectMocks private RagServiceImpl ragService;

    private static final float[] DUMMY_VEC = new float[1536];
    private static final Long DOC_ID = 1L;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < DUMMY_VEC.length; i++) {
            DUMMY_VEC[i] = 0.01f * (i % 100);
        }
        ReflectionTestUtils.setField(ragRetrievalService, "vectorBackend", "postgres");
        ReflectionTestUtils.setField(ragService, "ragRetrievalService", ragRetrievalService);
    }

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

    @Test
    @DisplayName("ingestDocument: splits text into chunks, embeds, upserts, saves chunks, marks indexed")
    void ingestDocument_fullPipeline() {
        String text = "西安".repeat(500);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/test.txt");
        doc.setRegion("西安市");
        doc.setDocType("text");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);
        when(ossClient.downloadDocument("rag/docs/test.txt")).thenReturn(textBytes);
        when(plainTextExtractor.extract(textBytes)).thenReturn(text);
        when(embeddingService.embedBatch(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> DUMMY_VEC.clone()).toList();
        });

        ragService.ingestDocument(DOC_ID);

        verify(embeddingService, atLeastOnce()).embedBatch(any());
        verify(dashVectorClient, never()).upsert(anyString(), any(float[].class), anyMap());
        verify(ragChunkMapper, atLeastOnce()).insertBatchPostgres(any());
        verify(ragDocumentMapper).updateLifecycle(eq(DOC_ID), eq("indexed"), eq(100), eq(0), isNull(), isNull());
    }

    @Test
    @DisplayName("ingestDocument: docType=pdf 路由到 PdfTextExtractor")
    void ingestDocument_routesToPdfExtractor() {
        byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46};
        String extractedText = "秦始皇兵马俑简介".repeat(100);

        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/guide.pdf");
        doc.setRegion("西安市");
        doc.setDocType("pdf");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);
        when(ossClient.downloadDocument("rag/docs/guide.pdf")).thenReturn(pdfBytes);
        when(pdfTextExtractor.extract(pdfBytes)).thenReturn(extractedText);
        when(embeddingService.embedBatch(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> DUMMY_VEC.clone()).toList();
        });

        ragService.ingestDocument(DOC_ID);

        verify(pdfTextExtractor).extract(pdfBytes);
        verify(plainTextExtractor, never()).extract(any());
        verify(ragDocumentMapper).updateLifecycle(eq(DOC_ID), eq("indexed"), eq(100), eq(0), isNull(), isNull());
    }

    @Test
    @DisplayName("ingestDocument: PDF 解析失败时标记文档为 failed")
    void ingestDocument_marksFailedOnPdfExtractionError() {
        byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46};

        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/corrupt.pdf");
        doc.setRegion("北京市");
        doc.setDocType("pdf");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);
        when(ossClient.downloadDocument(any())).thenReturn(pdfBytes);
        when(pdfTextExtractor.extract(pdfBytes))
                .thenThrow(new DocumentExtractionException("PDF parse failed: corrupted stream", null));

        ragService.ingestDocument(DOC_ID);

        verify(ragDocumentMapper).updateLifecycle(eq(DOC_ID), eq("failed"), eq(0), eq(0), eq("TEXT_EXTRACT_FAILED"), anyString());
        verify(embeddingService, never()).embedBatch(any());
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

        verify(ragDocumentMapper).updateLifecycle(eq(DOC_ID), eq("failed"), eq(0), eq(0), eq("DOWNLOAD_FAILED"), anyString());
        verify(embeddingService, never()).embedBatch(any());
    }

    @Test
    @DisplayName("ingestDocument: does nothing when document not found")
    void ingestDocument_noopWhenDocumentNotFound() {
        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(null);

        ragService.ingestDocument(DOC_ID);

        verify(ossClient, never()).downloadDocument(any());
    }

    @Test
    @DisplayName("splitIntoChunks: keeps headings with paragraphs and list items together")
    void splitIntoChunks_preservesDocumentStructure() {
        String text = """
                # 西安三日游

                西安城墙适合安排在抵达后半天，傍晚光线更适合拍照。

                ## 兵马俑
                - 建议上午出发
                - 预留讲解时间
                - 回程可接华清宫

                ## 回民街
                晚间适合小吃体验，注意避开过度商业化摊位。
                """;

        List<String> chunks = ReflectionTestUtils.invokeMethod(ragService, "splitIntoChunks", text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0))
                .contains("# 西安三日游", "西安城墙")
                .contains("## 兵马俑", "- 建议上午出发", "- 回程可接华清宫")
                .contains("## 回民街", "晚间适合小吃体验");
    }

    @Test
    @DisplayName("splitIntoChunks: falls back to overlapping windows for oversized blocks")
    void splitIntoChunks_splitsOversizedBlocks() {
        String text = "# 超长段落\n" + "秦始皇陵博物院".repeat(120);

        List<String> chunks = ReflectionTestUtils.invokeMethod(ragService, "splitIntoChunks", text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(768));
        assertThat(chunks.get(0)).startsWith("# 超长段落");
    }

    @Test
    @DisplayName("queryChunks: returns chunk texts from DashVector results")
    void queryChunks_returnsChunkTexts() {
        ReflectionTestUtils.setField(ragRetrievalService, "vectorBackend", "dashvector");
        when(embeddingService.embed("西安市 西安美食")).thenReturn(DUMMY_VEC);
        DashVectorClient.DashVectorResult r1 = new DashVectorClient.DashVectorResult("id1", 0.95f,
                Map.of("region", "西安市", "chunkText", "兵马俑简介"));
        DashVectorClient.DashVectorResult r2 = new DashVectorClient.DashVectorResult("id2", 0.88f,
                Map.of("region", "西安市", "chunkText", "回民街美食推荐"));
        when(dashVectorClient.search(any(), eq(12), eq("西安市"))).thenReturn(List.of(r1, r2));

        List<String> chunks = ragService.queryChunks("西安美食", "西安市", 3);

        assertThat(chunks).containsExactly("兵马俑简介", "回民街美食推荐");
    }

    @Test
    @DisplayName("hybridSearch: fuses pgvector and full-text scores with source metadata")
    void hybridSearch_fusesVectorAndKeywordResults() {
        when(embeddingService.embed("西安市 兵马俑历史")).thenReturn(DUMMY_VEC);

        RagSearchMatch vectorOnly = searchMatch(10L, "兵马俑历史故事", 0.82d, 0.0d, "manual");
        RagSearchMatch bothLegsVector = searchMatch(11L, "秦始皇陵游览建议", 0.88d, 0.0d, "controlled_crawl");
        RagSearchMatch bothLegsKeyword = searchMatch(11L, "秦始皇陵游览建议", 0.0d, 0.92d, "controlled_crawl");
        RagSearchMatch keywordOnly = searchMatch(12L, "回民街美食路线", 0.0d, 0.73d, "manual");

        when(ragChunkMapper.searchByPgVector(anyString(), eq("西安市"), anyList(), eq(12)))
                .thenReturn(List.of(vectorOnly, bothLegsVector));
        when(ragChunkMapper.searchByFullText(eq("西安市 兵马俑历史"), eq("西安市"), anyList(), eq(12)))
                .thenReturn(List.of(bothLegsKeyword, keywordOnly));

        List<RagSearchResult> results = ragService.hybridSearch("兵马俑历史", "西安市", 3);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).chunkId()).isEqualTo(11L);
        assertThat(results.get(0).vectorScore()).isEqualTo(0.88d);
        assertThat(results.get(0).bm25Score()).isEqualTo(0.92d);
        assertThat(results.get(0).sourceType()).isEqualTo("static_knowledge");
        assertThat(results.get(0).sourceName()).isEqualTo("controlled_crawl");
        assertThat(results.get(0).rankReason()).contains("hybrid:", "sourceType=static_knowledge");
    }

    @Test
    @DisplayName("registerDocument: rejects real-time sources so tool data is not mixed into RAG")
    void registerDocument_rejectsRealtimeSource() {
        assertThatThrownBy(() -> ragService.registerDocument(
                "rag/docs/weather.txt",
                "实时天气",
                "西安市",
                "text",
                "realtime_tool",
                "weather_api",
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实时信息");

        verify(ragDocumentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("queryChunks: returns empty list when DashVector throws")
    void queryChunks_returnsEmptyOnError() {
        ReflectionTestUtils.setField(ragRetrievalService, "vectorBackend", "dashvector");
        when(embeddingService.embed(any())).thenReturn(DUMMY_VEC);
        when(dashVectorClient.search(any(), anyInt(), any())).thenThrow(new RuntimeException("network error"));

        List<String> chunks = ragService.queryChunks("test query", "西安市", 5);

        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("queryChunks: default postgres backend does not fall back to DashVector implicitly")
    void queryChunks_defaultPostgresDoesNotUseDashVectorFallback() {
        when(embeddingService.embed("西安市 西安美食")).thenReturn(DUMMY_VEC);
        when(ragChunkMapper.searchByPgVector(anyString(), eq("西安市"), anyList(), eq(12)))
                .thenThrow(new RuntimeException("pgvector unavailable"));

        List<String> chunks = ragService.queryChunks("西安美食", "西安市", 3);

        assertThat(chunks).isEmpty();
        verify(dashVectorClient, never()).search(any(), anyInt(), any());
    }

    @Test
    @DisplayName("retryIngest: clears prior chunks, increments retry count, and reruns ingestion")
    void retryIngest_resetsLifecycleAndReingests() {
        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/retry.txt");
        doc.setDocType("text");
        doc.setRetryCount(2);
        doc.setStatus("failed");

        RagDocument refreshed = new RagDocument();
        refreshed.setId(DOC_ID);
        refreshed.setOssKey("rag/docs/retry.txt");
        refreshed.setDocType("text");
        refreshed.setRetryCount(3);
        refreshed.setStatus("pending");

        RagChunk chunk = new RagChunk();
        chunk.setDashvectorId("dv-1");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc, refreshed, refreshed);
        when(ragChunkMapper.findByDocumentId(DOC_ID)).thenReturn(List.of(chunk));
        when(ossClient.downloadDocument("rag/docs/retry.txt"))
                .thenReturn("retry body".getBytes(StandardCharsets.UTF_8));
        when(plainTextExtractor.extract(any())).thenReturn("retry body");
        when(embeddingService.embedBatch(any())).thenReturn(List.of(DUMMY_VEC.clone()));

        ragService.retryIngest(DOC_ID);

        verify(dashVectorClient).delete(List.of("dv-1"));
        verify(ragChunkMapper, atLeastOnce()).deleteByDocumentId(DOC_ID);
        verify(ragDocumentMapper).incrementRetryCount(DOC_ID);
        verify(ragDocumentMapper).updateLifecycle(eq(DOC_ID), eq("pending"), eq(0), eq(3), isNull(), isNull());
        verify(ragDocumentMapper).updateLifecycle(eq(DOC_ID), eq("indexed"), eq(100), eq(3), isNull(), isNull());
    }

    @Test
    @DisplayName("disableDocument: updates mapper after existence check")
    void disableDocument_updatesMapper() {
        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);

        ragService.disableDocument(DOC_ID);

        verify(ragDocumentMapper).disableDocument(DOC_ID);
    }

    @Test
    @DisplayName("reenableDocument: updates mapper after existence check")
    void reenableDocument_updatesMapper() {
        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);

        ragService.reenableDocument(DOC_ID);

        verify(ragDocumentMapper).reenableDocument(DOC_ID);
    }

    @Test
    @DisplayName("deleteDocument: removes DashVector docs, chunks, OSS object, and DB row")
    void deleteDocument_removesArtifactsAndRecord() {
        RagDocument doc = new RagDocument();
        doc.setId(DOC_ID);
        doc.setOssKey("rag/docs/delete.txt");
        RagChunk chunk = new RagChunk();
        chunk.setDashvectorId("dv-delete");

        when(ragDocumentMapper.findById(DOC_ID)).thenReturn(doc);
        when(ragChunkMapper.findByDocumentId(DOC_ID)).thenReturn(List.of(chunk));

        ragService.deleteDocument(DOC_ID);

        verify(dashVectorClient).delete(List.of("dv-delete"));
        verify(ragChunkMapper).deleteByDocumentId(DOC_ID);
        verify(ossClient).deleteDocument("rag/docs/delete.txt");
        verify(ragDocumentMapper).deleteById(DOC_ID);
    }

    private RagSearchMatch searchMatch(Long id,
                                       String chunkText,
                                       Double vectorScore,
                                       Double bm25Score,
                                       String sourceName) {
        RagSearchMatch match = new RagSearchMatch();
        match.setChunkId(id);
        match.setDocumentId(DOC_ID);
        match.setChunkText(chunkText);
        match.setTitle("西安知识库");
        match.setRegion("西安市");
        match.setSourceType("static_knowledge");
        match.setSourceName(sourceName);
        match.setSourceUrl("https://example.test/rag/" + id);
        match.setVectorScore(vectorScore);
        match.setBm25Score(bm25Score);
        match.setFreshnessScore(0.0d);
        return match;
    }
}
