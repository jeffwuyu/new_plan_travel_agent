package com.travelagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.RagSearchResult;
import com.travelagent.service.rag.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagController Tests")
class RagControllerTest {

    @Mock private RagService ragService;
    @Mock private RagDocumentMapper ragDocumentMapper;

    @InjectMocks private RagController ragController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(ragController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("registerDocument: admin (userLevel=3) can register a document")
    void registerDocument_adminSuccess() throws Exception {
        RagDocument doc = new RagDocument();
        doc.setId(1L);
        doc.setOssKey("rag/docs/xian.txt");
        doc.setTitle("西安旅游攻略");
        doc.setRegion("西安市");
        doc.setDocType("text");
        doc.setStatus("pending");

        when(ragService.registerDocument(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);

        mockMvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userLevel", 3)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ossKey", "rag/docs/xian.txt",
                                "title", "西安旅游攻略",
                                "region", "西安市",
                                "docType", "text"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.ossKey").value("rag/docs/xian.txt"));
    }

    @Test
    @DisplayName("registerDocument: non-admin (userLevel=1) receives HTTP 403")
    void registerDocument_nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userLevel", 1)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ossKey", "rag/docs/xian.txt",
                                "title", "Test",
                                "region", "西安市",
                                "docType", "text"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("registerDocument: missing ossKey returns HTTP 400")
    void registerDocument_missingOssKey_returns400() throws Exception {
        mockMvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userLevel", 3)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Test",
                                "docType", "text"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("ingestDocument: triggers async ingest and returns 200 immediately")
    void ingestDocument_asyncTrigger() throws Exception {
        RagDocument doc = new RagDocument();
        doc.setId(1L);
        doc.setStatus("pending");

        when(ragDocumentMapper.findById(1L)).thenReturn(doc);
        doNothing().when(ragService).ingestDocument(1L);

        mockMvc.perform(post("/api/rag/documents/1/ingest")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(ragService).ingestDocument(1L);
    }

    @Test
    @DisplayName("ingestDocument: returns HTTP 404 when document not found")
    void ingestDocument_notFound() throws Exception {
        when(ragDocumentMapper.findById(99L)).thenReturn(null);

        mockMvc.perform(post("/api/rag/documents/99/ingest")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("retryDocument: admin can trigger retry")
    void retryDocument_adminSuccess() throws Exception {
        doNothing().when(ragService).retryIngest(7L);

        mockMvc.perform(post("/api/rag/documents/7/retry")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(ragService).retryIngest(7L);
    }

    @Test
    @DisplayName("disableDocument: admin can disable a document")
    void disableDocument_adminSuccess() throws Exception {
        doNothing().when(ragService).disableDocument(8L);

        mockMvc.perform(post("/api/rag/documents/8/disable")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(ragService).disableDocument(8L);
    }

    @Test
    @DisplayName("reenableDocument: admin can reenable a document")
    void reenableDocument_adminSuccess() throws Exception {
        doNothing().when(ragService).reenableDocument(9L);

        mockMvc.perform(post("/api/rag/documents/9/reenable")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(ragService).reenableDocument(9L);
    }

    @Test
    @DisplayName("deleteDocument: admin can delete a document")
    void deleteDocument_adminSuccess() throws Exception {
        doNothing().when(ragService).deleteDocument(10L);

        mockMvc.perform(post("/api/rag/documents/10/delete")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(ragService).deleteDocument(10L);
    }

    @Test
    @DisplayName("getDocument: returns document for existing id")
    void getDocument_found() throws Exception {
        RagDocument doc = new RagDocument();
        doc.setId(2L);
        doc.setStatus("indexed");

        when(ragDocumentMapper.findById(2L)).thenReturn(doc);

        mockMvc.perform(get("/api/rag/documents/2")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("indexed"));
    }

    @Test
    @DisplayName("getDocument: returns HTTP 404 for non-existent id")
    void getDocument_notFound() throws Exception {
        when(ragDocumentMapper.findById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/rag/documents/99")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("queryChunks: returns chunk list for valid admin request")
    void queryChunks_returnsResults() throws Exception {
        when(ragService.hybridSearch("西安美食", "西安市", 3))
                .thenReturn(List.of(new RagSearchResult(
                        10L,
                        1L,
                        "兵马俑简介",
                        "西安旅游攻略",
                        "西安市",
                        "static_knowledge",
                        "manual",
                        "https://example.test/xian",
                        0.92d,
                        0.81d,
                        0.0d,
                        0.72d,
                        "hybrid: vectorScore=0.9200, bm25Score=0.8100")));

        mockMvc.perform(get("/api/rag/query")
                        .param("text", "西安美食")
                        .param("region", "西安市")
                        .param("topK", "3")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].chunkText").value("兵马俑简介"))
                .andExpect(jsonPath("$.data[0].sourceType").value("static_knowledge"))
                .andExpect(jsonPath("$.data[0].finalScore").value(0.72d));
    }

    @Test
    @DisplayName("queryChunks: topK > 20 returns HTTP 400")
    void queryChunks_topKOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/rag/query")
                        .param("text", "西安美食")
                        .param("topK", "50")
                        .requestAttr("userLevel", 3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
