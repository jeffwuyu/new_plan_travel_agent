package com.travelagent.service.task;

import com.travelagent.model.dto.LocationCandidateItem;

import java.util.List;

public interface OriginCandidateService {

    List<LocationCandidateItem> generateCandidates(String region, String currentLocationQuery);
}
