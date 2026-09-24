package com.travelagent.agent.requirements;

import com.travelagent.model.dto.CreateTaskRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TravelRequirementParser {

    private static final Map<String, Integer> CHINESE_DIGITS = Map.ofEntries(
            Map.entry("一", 1),
            Map.entry("二", 2),
            Map.entry("两", 2),
            Map.entry("三", 3),
            Map.entry("四", 4),
            Map.entry("五", 5),
            Map.entry("六", 6),
            Map.entry("七", 7),
            Map.entry("八", 8),
            Map.entry("九", 9),
            Map.entry("十", 10)
    );

    public TravelConstraints parse(String text) {
        TravelConstraints constraints = new TravelConstraints();
        String normalized = normalize(text);
        constraints.setRawText(normalized);
        constraints.setDeparture(firstMatch(normalized,
                "从(?<value>[\\p{IsHan}A-Za-z0-9·\\-\\s]{1,20}?)(?:出发)?(?:去|到|前往)",
                "(?<value>[\\p{IsHan}A-Za-z0-9·\\-\\s]{1,20}?)(?:出发|启程)"));
        constraints.setDestination(firstMatch(normalized,
                "(?:去|到|前往)(?<value>[\\p{IsHan}A-Za-z0-9·\\-\\s]{1,30}?)(?:玩|旅游|旅行|游|[0-9一二两三四五六七八九十]+天|,|。|$)",
                "目的地(?:是|为)?(?<value>[\\p{IsHan}A-Za-z0-9·\\-\\s]{1,30}?)(?:,|。|$)"));
        constraints.setDays(extractDays(normalized));
        LocalDate startDate = extractStartDate(normalized);
        constraints.setStartDate(startDate);
        if (startDate != null && constraints.getDays() != null && constraints.getDays() > 0) {
            constraints.setEndDate(startDate.plusDays(constraints.getDays() - 1L));
        }
        constraints.setBudgetYuan(extractBudget(normalized));
        constraints.setPeopleCount(extractPeopleCount(normalized));
        constraints.setTransportPreference(extractKeywordList(normalized, Map.of(
                "飞机", "飞机",
                "高铁", "高铁",
                "火车", "火车",
                "自驾", "自驾",
                "地铁", "市内公共交通",
                "公交", "市内公共交通",
                "公共交通", "市内公共交通",
                "打车", "打车"
        )));
        constraints.setHotelPreference(joinMatched(normalized, "靠近地铁", "近地铁", "交通便利", "安静",
                "亲子", "住得方便", "方便一点", "预算酒店", "民宿"));
        constraints.setAttractionPreference(extractKeywordList(normalized, Map.of(
                "历史", "历史",
                "人文", "人文",
                "自然", "自然",
                "亲子", "亲子",
                "美食", "美食",
                "拍照", "拍照",
                "购物", "购物",
                "博物馆", "博物馆",
                "室内", "室内"
        )));
        constraints.setFoodPreference(joinMatched(normalized, "本地美食", "当地美食", "清淡", "不吃辣", "素食", "小吃"));
        constraints.setTravelPace(extractTravelPace(normalized));
        constraints.setSpecialGroups(extractKeywordList(normalized, Map.of(
                "老人", "老人",
                "小孩", "儿童",
                "孩子", "儿童",
                "亲子", "亲子",
                "行动不便", "行动不便者"
        )));
        constraints.setBookingRequired(extractBookingRequired(normalized));
        constraints.setAvoid(extractAvoid(normalized));
        return constraints.refreshMissingFields();
    }

    public TravelConstraints parse(String text, TravelConstraints existing) {
        if (existing == null) {
            return parse(text);
        }
        return existing.mergeFrom(parse(text));
    }

    public TravelConstraints fromCreateTaskRequest(CreateTaskRequest request) {
        TravelConstraints constraints = new TravelConstraints();
        if (request == null) {
            return constraints.refreshMissingFields();
        }
        constraints.setRawText(request.getUserIntent());
        constraints.setDeparture(request.getStartLocationQuery());
        constraints.setDestination(firstNonBlank(request.getRegion(), request.getCityName(), request.getProvinceName()));
        if (request.getStartTime() != null) {
            constraints.setStartDate(request.getStartTime().toLocalDate());
        }
        if (request.getEndTime() != null) {
            constraints.setEndDate(request.getEndTime().toLocalDate());
        }
        if (request.getStartTime() != null && request.getEndTime() != null) {
            constraints.setDays((int) Duration.between(
                    request.getStartTime().toLocalDate().atStartOfDay(),
                    request.getEndTime().toLocalDate().atStartOfDay()).toDays() + 1);
        }
        constraints.setBudgetYuan(request.getTotalBudgetYuan());
        constraints.setPeopleCount(request.getAdultCount());
        constraints.setTransportPreference(request.getTravelMode() == null ? new ArrayList<>() : List.of(request.getTravelMode()));
        constraints.setAttractionPreference(request.getPreferenceKeywords() == null
                ? new ArrayList<>()
                : new ArrayList<>(request.getPreferenceKeywords()));
        if (request.getAccommodationTypes() != null && !request.getAccommodationTypes().isEmpty()) {
            constraints.setHotelPreference(String.join(",", request.getAccommodationTypes()));
        }
        return constraints.refreshMissingFields();
    }

    public TravelConstraints merge(TravelConstraints base, TravelConstraints update) {
        return (base == null ? new TravelConstraints() : base).mergeFrom(update);
    }

    private Integer extractDays(String text) {
        Matcher matcher = Pattern.compile("(?<num>\\d+|[一二两三四五六七八九十])\\s*天").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return toInt(matcher.group("num"));
    }

    private LocalDate extractStartDate(String text) {
        Matcher matcher = Pattern.compile("(?<year>20\\d{2})[年/-](?<month>\\d{1,2})[月/-](?<day>\\d{1,2})").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return LocalDate.of(Integer.parseInt(matcher.group("year")),
                Integer.parseInt(matcher.group("month")),
                Integer.parseInt(matcher.group("day")));
    }

    private BigDecimal extractBudget(String text) {
        Matcher matcher = Pattern.compile("(?:预算|花费|控制在|改成|以内|不超过)\\D{0,8}(?<num>\\d{3,7})(?:\\s*元|块|rmb)?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return new BigDecimal(matcher.group("num"));
    }

    private Integer extractPeopleCount(String text) {
        String value = firstMatch(text,
                "(?<value>\\d+|[一二两三四五六七八九十])\\s*(?:个人|人|位)",
                "一家(?<value>\\d+|[一二两三四五六七八九十])口");
        return toInt(value);
    }

    private String extractTravelPace(String text) {
        if (containsAny(text, "轻松", "慢旅行", "少走路", "不想太早起")) {
            return "relaxed";
        }
        if (containsAny(text, "高强度", "多看", "赶一点")) {
            return "intensive";
        }
        if (containsAny(text, "普通", "经典")) {
            return "normal";
        }
        return null;
    }

    private Boolean extractBookingRequired(String text) {
        if (containsAny(text, "预约", "门票", "购票", "票种", "约不上")) {
            return true;
        }
        return null;
    }

    private List<String> extractAvoid(String text) {
        List<String> values = new ArrayList<>();
        for (String pattern : List.of("不想(?<value>[\\p{IsHan}A-Za-z0-9]{1,12})",
                "避免(?<value>[\\p{IsHan}A-Za-z0-9]{1,12})",
                "少(?<value>走路)")) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            while (matcher.find()) {
                addUnique(values, matcher.group("value"));
            }
        }
        return values;
    }

    private List<String> extractKeywordList(String text, Map<String, String> keywordMap) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(keywordMap).entrySet()) {
            if (text.contains(entry.getKey())) {
                addUnique(values, entry.getValue());
            }
        }
        return values;
    }

    private String joinMatched(String text, String... keywords) {
        List<String> values = new ArrayList<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                addUnique(values, keyword);
            }
        }
        return values.isEmpty() ? null : String.join("，", values);
    }

    private String firstMatch(String text, String... patterns) {
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (matcher.find()) {
                String value = matcher.group("value");
                return value == null ? null : value.trim().replaceAll("^[,，。\\s]+|[,，。\\s]+$", "");
            }
        }
        return null;
    }

    private Integer toInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        if ("十".equals(value)) {
            return 10;
        }
        if (value.startsWith("十")) {
            return 10 + CHINESE_DIGITS.getOrDefault(value.substring(value.length() - 1), 0);
        }
        if (value.endsWith("十")) {
            return CHINESE_DIGITS.getOrDefault(value.substring(0, 1), 0) * 10;
        }
        return CHINESE_DIGITS.get(value);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().replace('，', ',').replace('。', '.');
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value.trim())) {
            values.add(value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

