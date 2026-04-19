package com.travelagent.model.dto;

import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 中文注释：DTO 类，用于在接口或服务之间传递 Create Task Request 数据。
 */

@Data
public class CreateTaskRequest {

    @NotBlank(message = "目的地区域不能为空")
    @Size(max = 128, message = "区域名称过长")
    private String region;

    @NotBlank(message = "旅行意图不能为空")
    @Size(max = 500, message = "旅行意图描述过长")
    private String userIntent;

    @Min(value = 1, message = "最少1天")
    @Max(value = 14, message = "最多14天")
    private int totalDays = 3;

    @Min(value = 1, message = "每天至少1个景点")
    @Max(value = 6, message = "每天最多6个景点")
    private int attractionsPerDay = 3;

    /** User preference tags, e.g. ["历史", "美食", "自然"] */
    private List<String> preferenceKeywords;

    /** driving | walking | transit */
    private String travelMode = "driving";
}
