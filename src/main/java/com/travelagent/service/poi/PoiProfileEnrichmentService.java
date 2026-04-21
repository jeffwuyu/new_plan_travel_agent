package com.travelagent.service.poi;

import com.travelagent.model.entity.Attraction;

import java.util.List;
import java.util.Map;

public interface PoiProfileEnrichmentService {

    Attraction saveOrUpdateFromAmapPoi(Map<String, Object> amapPoi);

    List<Attraction> saveOrUpdateAll(List<Map<String, Object>> amapPois);
}
