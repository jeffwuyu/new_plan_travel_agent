package com.travelagent.service.task.impl;

import com.travelagent.client.amap.AmapClient;
import com.travelagent.mapper.AttractionMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.task.OriginCandidateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OriginCandidateServiceImpl implements OriginCandidateService {

    @Autowired private AttractionMapper attractionMapper;
    @Autowired private AmapClient amapClient;

    /**
     * 处理generateCandidates。
     * @param region 区域信息
     * @param currentLocationQuery c ur re nt Lo ca ti on Qu er y 参数
     * @return 返回处理后的列表结果。
     */
    @Override
    public List<LocationCandidateItem> generateCandidates(String region, String currentLocationQuery) {
        Map<String, LocationCandidateItem> merged = new LinkedHashMap<>();

        for (Attraction attraction : attractionMapper.searchByRegionAndKeyword(region, currentLocationQuery, 5)) {
            if (attraction.getLatitude() == null || attraction.getLongitude() == null) {
                continue;
            }
            LocationCandidateItem item = new LocationCandidateItem();
            item.setCandidateId(attraction.getAmapPoiId() != null && !attraction.getAmapPoiId().isBlank()
                    ? attraction.getAmapPoiId()
                    : "attr:" + attraction.getId());
            item.setName(attraction.getName());
            item.setRegion(attraction.getRegion());
            item.setDistrict(attraction.getDistrict());
            item.setCategory(attraction.getCategory());
            item.setAddress(attraction.getAddress());
            item.setLatitude(attraction.getLatitude().doubleValue());
            item.setLongitude(attraction.getLongitude().doubleValue());
            item.setSource("catalog");
            merged.put(item.getCandidateId(), item);
        }

        try {
            Map<String, Object> geocode = amapClient.geocode(currentLocationQuery, region);
            LocationCandidateItem item = new LocationCandidateItem();
            item.setCandidateId("geo:" + currentLocationQuery.trim().toLowerCase());
            item.setName(currentLocationQuery);
            item.setRegion(region);
            item.setLatitude(((Number) geocode.get("lat")).doubleValue());
            item.setLongitude(((Number) geocode.get("lng")).doubleValue());
            item.setAdcode(String.valueOf(geocode.getOrDefault("adcode", "")));
            item.setSource("geocode");
            merged.putIfAbsent(item.getCandidateId(), item);
        } catch (Exception ignored) {
            // Fallback to catalog results only.
        }

        return new ArrayList<>(merged.values());
    }
}
