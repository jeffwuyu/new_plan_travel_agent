package com.travelagent.controller;

import com.travelagent.client.oss.OssClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.RagDocumentMapper;
import com.travelagent.model.dto.Result;
import com.travelagent.model.dto.UploadRagDocumentResponse;
import com.travelagent.model.entity.RagDocument;
import com.travelagent.service.rag.RagService;
import com.travelagent.service.rag.RagSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Tag(name = "RAG 管理", description = "文档入库与向量检索管理（需 ADMIN 权限）")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired private RagService ragService;
    @Autowired private RagDocumentMapper ragDocumentMapper;
    @Autowired private OssClient ossClient;

    private void requireAdmin(HttpServletRequest request) {
        int userLevel = JwtAuthInterceptor.getUserLevel(request);
        if (userLevel != 3) {
            throw new BusinessException(403, "此操作需要管理员权限（ADMIN）");
        }
    }

    @Operation(summary = "上传 RAG 文档文件",
            description = "multipart/form-data 上传，验证后写入 OSS，注册 DB，并触发异步入库。最大 50MB。")
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UploadRagDocumentResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam("docType") String docType,
            @RequestParam(value = "sourceType", required = false, defaultValue = "static_knowledge") String sourceType,
            @RequestParam(value = "sourceName", required = false, defaultValue = "manual") String sourceName,
            @RequestParam(value = "sourceUrl", required = false) String sourceUrl,
            HttpServletRequest request) {
        requireAdmin(request);

        if (!List.of("pdf", "markdown", "text").contains(docType)) {
            throw new BusinessException(400, "docType 必须为 pdf、markdown 或 text");
        }
        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        if (file.getSize() > 50L * 1024 * 1024) {
            throw new BusinessException(400, "文件大小不超过 50MB");
        }

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._\\-]", "_")
                : "document";
        byte[] prefix = new byte[8];
        new Random().nextBytes(prefix);
        String ossKey = "rag/documents/" + HexFormat.of().formatHex(prefix) + "-" + originalFilename;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "文件读取失败: " + e.getMessage());
        }

        ossClient.uploadDocument(ossKey, bytes, resolveContentType(docType));
        RagDocument doc = ragService.registerDocument(ossKey, title, region, docType,
                sourceType, sourceName, sourceUrl, null);
        ragService.ingestDocument(doc.getId());

        return Result.success(new UploadRagDocumentResponse(
                doc.getId(), ossKey, title, region, docType));
    }

    private String resolveContentType(String docType) {
        return switch (docType) {
            case "pdf" -> "application/pdf";
            case "markdown" -> "text/markdown";
            default -> "text/plain";
        };
    }

    @Operation(summary = "注册 RAG 文档元信息",
            description = "将文档元信息写入 rag_documents 表（status=pending），不触发入库。")
    @PostMapping("/documents")
    public Result<RagDocument> registerDocument(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        requireAdmin(request);

        String ossKey = body.get("ossKey");
        String title = body.get("title");
        String region = body.get("region");
        String docType = body.get("docType");
        String sourceType = body.getOrDefault("sourceType", "static_knowledge");
        String sourceName = body.getOrDefault("sourceName", "manual");
        String sourceUrl = body.get("sourceUrl");
        String metadataJson = body.get("metadataJson");

        if (ossKey == null || ossKey.isBlank()) {
            throw new BusinessException(400, "ossKey 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException(400, "title 不能为空");
        }
        if (docType == null || !List.of("pdf", "markdown", "text").contains(docType)) {
            throw new BusinessException(400, "docType 必须为 pdf、markdown 或 text");
        }

        RagDocument doc = ragService.registerDocument(ossKey, title, region, docType,
                sourceType, sourceName, sourceUrl, metadataJson);
        return Result.success(doc);
    }

    @Operation(summary = "触发 RAG 文档异步入库",
            description = "立即返回，后台线程执行：OSS 下载 -> 分块 -> 向量化 -> MySQL/DashVector。")
    @PostMapping("/documents/{id}/ingest")
    public Result<Void> ingestDocument(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);

        RagDocument doc = ragDocumentMapper.findById(id);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在: id=" + id);
        }

        ragService.ingestDocument(id);
        return Result.success();
    }

    @PostMapping("/documents/{id}/retry")
    public Result<Void> retryDocument(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        ragService.retryIngest(id);
        return Result.success();
    }

    @PostMapping("/documents/{id}/disable")
    public Result<Void> disableDocument(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        ragService.disableDocument(id);
        return Result.success();
    }

    @PostMapping("/documents/{id}/reenable")
    public Result<Void> reenableDocument(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        ragService.reenableDocument(id);
        return Result.success();
    }

    @PostMapping("/documents/{id}/delete")
    public Result<Void> deleteDocument(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        ragService.deleteDocument(id);
        return Result.success();
    }

    @Operation(summary = "查询 RAG 文档状态",
            description = "返回 rag_documents 记录，通过 status 字段判断入库进度（pending/indexing/indexed/failed/disabled）。")
    @GetMapping("/documents/{id}")
    public Result<RagDocument> getDocument(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);

        RagDocument doc = ragDocumentMapper.findById(id);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在: id=" + id);
        }
        return Result.success(doc);
    }

    @Operation(summary = "列出所有 RAG 文档",
            description = "返回 rag_documents 全量列表，按创建时间倒序（管理员用）。")
    @GetMapping("/documents")
    public Result<List<RagDocument>> listDocuments(HttpServletRequest request) {
        requireAdmin(request);
        return Result.success(ragDocumentMapper.findAll());
    }

    @Operation(summary = "手动测试 RAG 检索",
            description = "用指定文本执行 Hybrid RAG，返回召回 chunk、来源、分数和融合排序依据（管理员调试用）。")
    @GetMapping("/query")
    public Result<List<RagSearchResult>> queryChunks(
            @RequestParam String text,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "5") int topK,
            HttpServletRequest request) {
        requireAdmin(request);

        if (text.isBlank()) {
            throw new BusinessException(400, "text 不能为空");
        }
        if (topK < 1 || topK > 20) {
            throw new BusinessException(400, "topK 必须在 1-20 之间");
        }

        return Result.success(ragService.hybridSearch(text, region, topK));
    }

    @Operation(summary = "结构化 RAG 检索")
    @PostMapping("/search")
    public Result<List<RagSearchResult>> search(@RequestBody RagSearchRequest body,
                                                HttpServletRequest request) {
        if (JwtAuthInterceptor.getUserId(request) == null) {
            throw new BusinessException(401, "未认证");
        }
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new BusinessException(400, "text 不能为空");
        }
        int topK = body.topK() == null ? 5 : body.topK();
        if (topK < 1 || topK > 20) throw new BusinessException(400, "topK 必须在 1-20 之间");
        return Result.success(ragService.hybridSearch(body.text().trim(), body.region(), topK));
    }

    public record RagSearchRequest(String text, String region, Integer topK) {}
}
