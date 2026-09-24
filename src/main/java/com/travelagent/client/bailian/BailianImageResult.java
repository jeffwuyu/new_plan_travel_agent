package com.travelagent.client.bailian;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BailianImageResult {

    private String requestId;
    private String taskId;
    private String imageUrl;
    private String status;
    private String errorCode;
    private String errorMessage;
}
