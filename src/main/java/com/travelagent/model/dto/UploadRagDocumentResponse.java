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
