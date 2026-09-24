package com.travelagent.model.dto;

import lombok.Data;

@Data
public class RouteMapGenerateRequest {
    private String style;
    private Boolean force;
}
