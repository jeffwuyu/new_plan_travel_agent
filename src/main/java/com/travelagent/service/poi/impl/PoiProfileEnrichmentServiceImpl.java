package com.travelagent.service.poi.impl;

import com.travelagent.mapper.AttractionMapper;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.poi.PoiProfileEnrichmentService;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PoiProfileEnrichmentServiceImpl implements PoiProfileEnrichmentService {

    @Autowired private AttractionMapper attractionMapper;
    @Autowired private JsonUtil jsonUtil;

    @Override
    public Attraction saveOrUpdateFromAmapPoi(Map<String, Object> amapPoi) {
        Attraction attraction = toAttraction(amapPoi);
        attractionMapper.upsert(attraction);
        return attraction;
    }

    @Override
    public List<Attraction> saveOrUpdateAll(List<Map<String, Object>> amapPois) {
        List<Attraction> attractions = new ArrayList<>();
        for (Map<String, Object> amapPoi : amapPois) {
            attractions.add(saveOrUpdateFromAmapPoi(amapPoi));
        }
        return attractions;
    }

    private Attraction toAttraction(Map<String, Object> amapPoi) {
        Attraction attraction = new Attraction();
        attraction.setAmapPoiId(stringValue(amapPoi.get("id")));
        attraction.setName(stringValue(amapPoi.get("name")));
        attraction.setRegion(firstNonBlank(
                stringValue(amapPoi.get("adname")),
                stringValue(amapPoi.get("region")),
                stringValue(amapPoi.get("cityname"))));
        attraction.setCity(stringValue(amapPoi.get("cityname")));
        attraction.setDistrict(stringValue(amapPoi.get("adname")));
        String category = stringValue(amapPoi.get("type"));
        attraction.setCategory(firstToken(category));
        attraction.setSubCategory(secondToken(category));
        attraction.setAddress(stringValue(amapPoi.get("address")));
        attraction.setDescription(stringValue(amapPoi.get("type")));
        attraction.setTagsJson(jsonUtil.toJson(extractTags(amapPoi)));
        attraction.setTransportAccessJson(jsonUtil.toJson(Map.of(
                "walkable", Boolean.TRUE,
                "metro", Boolean.FALSE,
                "parking", Boolean.TRUE
        )));
        attraction.setSource("amap_nearby");
        attraction.setCachedAt(LocalDateTime.now());
        attraction.setLastSyncedAt(LocalDateTime.now());
        attraction.setPopularityScore(decimalValue(amapPoi.get("importance")));

        String location = stringValue(amapPoi.get("location"));
        if (location.contains(",")) {
            String[] parts = location.split(",");
            attraction.setLongitude(new BigDecimal(parts[0].trim()));
            attraction.setLatitude(new BigDecimal(parts[1].trim()));
        }
        return attraction;
    }

    private List<String> extractTags(Map<String, Object> amapPoi) {
        Set<String> tags = new LinkedHashSet<>();
        String type = stringValue(amapPoi.get("type"));
        if (!type.isBlank()) {
            for (String part : type.split(";")) {
                String tag = part.trim();
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            }
        }
        String name = stringValue(amapPoi.get("name"));
        if (!name.isBlank()) {
            tags.add(name);
        }
        return new ArrayList<>(tags);
    }

    private String firstToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.split(";")[0].trim();
    }

    private String secondToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(";");
        return parts.length > 1 ? parts[1].trim() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
