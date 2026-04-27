package com.travelagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UploadRagDocumentResponse {

    private Long   documentId;
    private String ossKey;
    private String title;
    private String region;
    private String docType;
    private String status;

    /**
     * 初始化UploadRagDocumentResponse 实例。
     * @param documentId d oc um en tI d 参数
     * @param ossKey o ss Ke y 参数
     * @param title t it le 参数
     * @param region 区域信息
     * @param docType d oc Ty pe 参数
     */
    public UploadRagDocumentResponse(Long documentId, String ossKey, String title,
                                     String region, String docType) {
        this.documentId = documentId;
        this.ossKey     = ossKey;
        this.title      = title;
        this.region     = region;
        this.docType    = docType;
        this.status     = "pending";
    }
}
