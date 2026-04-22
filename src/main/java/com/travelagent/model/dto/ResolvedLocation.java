package com.travelagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ResolvedLocation {

    private String candidateId;
    private String name;
    private String region;
    private String district;
    private String address;
    private Double latitude;
    private Double longitude;
    private String adcode;
    private String source;
}
