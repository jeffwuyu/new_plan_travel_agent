package com.travelagent.agent.tools;

import com.travelagent.agent.mcp.McpToolExecutionService;
import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.amap.AmapClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool that fetches current or forecast weather for a city by its Amap city key.
 *
 * <p>Delegates to {@link AmapClient#getWeather(String)} and
 * {@link AmapClient#getWeatherForecast(String)} which cache responses in Redis
 * for 1 hour. The {@link IdempotentTool} layer adds a per-task
 * 24-hour idempotency guarantee so that a resumed task never re-calls the API
 * for a step that already completed.
 *
 * <p>Expected input keys: {@code adcode} or {@code city}; optional
 * {@code startDate}/{@code endDate} or {@code date}.
 *
 * <p>Output keys: {@code weather}, {@code temperature}, {@code windDirection},
 * {@code windPower}, {@code humidity}, {@code forecastDays}, {@code summary},
 * {@code constraintHints}, {@code outdoorRisk}, {@code adjustmentSuggestions},
 * {@code queryTime}, {@code cacheTtl}.
 */

/**
 * 中文注释：Agent 工具类，负责执行 Weather Tool 相关的工具调用能力。
 */

@Component
public class WeatherTool implements AgentTool {

    public static final String NAME = "weather";

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final int HIGH_TEMPERATURE_C = 32;
    private static final int EXTREME_HIGH_TEMPERATURE_C = 38;
    private static final int STRONG_WIND_LEVEL = 6;
    private static final int EXTREME_WIND_LEVEL = 8;

    @Autowired
    private AmapClient amapClient;

    @Autowired
    private McpToolExecutionService mcpToolExecutionService;

    /**
     * 获取name。
     * @return 返回处理结果。
     */
    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getSource() {
        return "amap:weather";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "anyOf", List.of(
                        Map.of("required", List.of("adcode")),
                        Map.of("required", List.of("city")),
                        Map.of("required", List.of("cityCode"))
                ),
                "properties", Map.of(
                        "adcode", Map.of("type", "string", "minLength", 1),
                        "city", Map.of("type", "string", "minLength", 1),
                        "cityCode", Map.of("type", "string", "minLength", 1),
                        "startDate", Map.of("type", "string", "format", "date"),
                        "endDate", Map.of("type", "string", "format", "date"),
                        "date", Map.of("type", "string", "format", "date"),
                        "days", Map.of("type", List.of("integer", "string"))
                )
        );
    }

    /**
     * 处理execute。
     * @param arguments 工具调用参数
     * @param idempotencyKey i de mp ot en cy Ke y 参数
     * @return 返回处理后的映射结果。
     */
    @Override
    @IdempotentTool(ttl = "24h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String cityKey = resolveCityKey(safeArguments);
        boolean forecastRequired = requiresForecast(safeArguments);
        log.debug("[WeatherTool] Fetching weather for cityKey={} forecastRequired={}", cityKey, forecastRequired);

        Map<String, Object> raw = fetchWeather(safeArguments, cityKey, forecastRequired);
        return enrichWeather(raw, safeArguments, cityKey);
    }

    private Map<String, Object> fetchWeather(Map<String, Object> arguments, String cityKey, boolean forecastRequired) {
        if (mcpToolExecutionService.isEnabled()) {
            try {
                return mcpToolExecutionService.execute(NAME, arguments);
            } catch (Exception e) {
                log.warn("[WeatherTool] MCP weather failed for cityKey={}, falling back to REST: {}",
                        cityKey, e.getMessage());
                Map<String, Object> fallback = new HashMap<>(fetchAmapWeather(cityKey, forecastRequired));
                fallback.put("mcpFallback", true);
                fallback.put("mcpProvider", "amap-rest");
                return fallback;
            }
        }
        return fetchAmapWeather(cityKey, forecastRequired);
    }

    private Map<String, Object> fetchAmapWeather(String cityKey, boolean forecastRequired) {
        return forecastRequired ? amapClient.getWeatherForecast(cityKey) : amapClient.getWeather(cityKey);
    }

    private Map<String, Object> enrichWeather(Map<String, Object> raw,
                                              Map<String, Object> arguments,
                                              String cityKey) {
        Map<String, Object> enriched = raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
        List<Map<String, Object>> forecastDays = filterForecastDays(extractForecastDays(enriched), arguments);
        if (!forecastDays.isEmpty()) {
            List<Map<String, Object>> assessedDays = new ArrayList<>();
            for (Map<String, Object> forecastDay : forecastDays) {
                assessedDays.add(assessForecastDay(forecastDay));
            }
            enriched.put("forecastDays", assessedDays);
        }

        WeatherRisk aggregateRisk = assessAggregateWeather(enriched);
        enriched.put("cityKey", cityKey);
        enriched.put("dateRange", buildDateRange(arguments));
        enriched.put("riskFactors", aggregateRisk.riskFactors());
        enriched.put("adjustmentSuggestions", aggregateRisk.suggestions());
        enriched.put("constraintHints", aggregateRisk.constraintHints());
        enriched.put("outdoorRisk", aggregateRisk.level());
        enriched.put("extremeWeatherRisk", aggregateRisk.extremeWeatherRisk());
        enriched.put("indoorPreferred", aggregateRisk.indoorPreferred());
        enriched.put("shortWalkPreferred", aggregateRisk.shortWalkPreferred());
        enriched.put("avoidRain", aggregateRisk.avoidRain());
        enriched.put("avoidWind", aggregateRisk.avoidWind());
        enriched.put("summary", buildSummary(enriched, aggregateRisk));
        enriched.putIfAbsent("source", getSource());
        enriched.put("cacheTtl", "PT1H");
        enriched.put("queryTime", Instant.now().toString());
        return enriched;
    }

    private String resolveCityKey(Map<String, Object> arguments) {
        String cityKey = firstNonBlank(arguments.get("adcode"), arguments.get("city"), arguments.get("cityCode"));
        if (cityKey.isBlank()) {
            throw new IllegalArgumentException("weather tool requires adcode or city");
        }
        return cityKey;
    }

    private boolean requiresForecast(Map<String, Object> arguments) {
        return hasText(arguments.get("startDate"))
                || hasText(arguments.get("endDate"))
                || hasText(arguments.get("date"))
                || hasText(arguments.get("days"));
    }

    private Map<String, Object> buildDateRange(Map<String, Object> arguments) {
        String startDate = firstNonBlank(arguments.get("startDate"), arguments.get("date"));
        String endDate = firstNonBlank(arguments.get("endDate"), arguments.get("date"));
        Map<String, Object> dateRange = new LinkedHashMap<>();
        if (!startDate.isBlank()) {
            dateRange.put("startDate", startDate);
        }
        if (!endDate.isBlank()) {
            dateRange.put("endDate", endDate);
        }
        if (hasText(arguments.get("days"))) {
            dateRange.put("days", String.valueOf(arguments.get("days")));
        }
        return dateRange;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractForecastDays(Map<String, Object> enriched) {
        Object forecastDays = enriched.get("forecastDays");
        if (!(forecastDays instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                normalized.add((Map<String, Object>) map);
            }
        }
        return normalized;
    }

    private List<Map<String, Object>> filterForecastDays(List<Map<String, Object>> forecastDays,
                                                         Map<String, Object> arguments) {
        if (forecastDays.isEmpty()) {
            return List.of();
        }
        LocalDate start = parseDate(firstNonBlank(arguments.get("startDate"), arguments.get("date")));
        LocalDate end = parseDate(firstNonBlank(arguments.get("endDate"), arguments.get("date")));
        int daysLimit = parsePositiveInt(arguments.get("days"));
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> forecastDay : forecastDays) {
            LocalDate date = parseDate(firstNonBlank(forecastDay.get("date")));
            boolean inRange = true;
            if (date != null && start != null && date.isBefore(start)) {
                inRange = false;
            }
            if (date != null && end != null && date.isAfter(end)) {
                inRange = false;
            }
            if (inRange) {
                filtered.add(forecastDay);
            }
        }
        if (filtered.isEmpty() && (start != null || end != null)) {
            filtered.addAll(forecastDays);
        }
        if (daysLimit > 0 && filtered.size() > daysLimit) {
            return new ArrayList<>(filtered.subList(0, daysLimit));
        }
        return filtered;
    }

    private Map<String, Object> assessForecastDay(Map<String, Object> forecastDay) {
        Map<String, Object> assessed = new LinkedHashMap<>(forecastDay);
        String weatherText = firstNonBlank(
                forecastDay.get("dayWeather"),
                forecastDay.get("nightWeather"),
                forecastDay.get("weather"));
        Integer highTemperature = parseMaxInteger(forecastDay.get("tempHigh"));
        Integer lowTemperature = parseMinInteger(forecastDay.get("tempLow"));
        Integer windPower = maxInteger(
                parseMaxInteger(forecastDay.get("dayWindPower")),
                parseMaxInteger(forecastDay.get("nightWindPower")));
        WeatherRisk risk = assessWeather(weatherText, highTemperature, lowTemperature, windPower);
        assessed.put("outdoorRisk", risk.level());
        assessed.put("riskFactors", risk.riskFactors());
        assessed.put("adjustmentSuggestions", risk.suggestions());
        return assessed;
    }

    private WeatherRisk assessAggregateWeather(Map<String, Object> weather) {
        List<Map<String, Object>> forecastDays = extractForecastDays(weather);
        if (forecastDays.isEmpty()) {
            return assessWeather(
                    firstNonBlank(weather.get("weather")),
                    parseMaxInteger(weather.get("temperature")),
                    parseMinInteger(weather.get("temperature")),
                    parseMaxInteger(weather.get("windPower")));
        }

        WeatherRisk aggregate = WeatherRisk.stable();
        for (Map<String, Object> forecastDay : forecastDays) {
            WeatherRisk dayRisk = assessWeather(
                    firstNonBlank(forecastDay.get("dayWeather"), forecastDay.get("nightWeather"), forecastDay.get("weather")),
                    parseMaxInteger(firstNonBlank(forecastDay.get("tempHigh"), forecastDay.get("temperature"))),
                    parseMinInteger(firstNonBlank(forecastDay.get("tempLow"), forecastDay.get("temperature"))),
                    maxInteger(parseMaxInteger(forecastDay.get("dayWindPower")), parseMaxInteger(forecastDay.get("nightWindPower"))));
            aggregate = aggregate.merge(dayRisk);
        }
        return aggregate;
    }

    private WeatherRisk assessWeather(String weatherText,
                                      Integer highTemperature,
                                      Integer lowTemperature,
                                      Integer windPower) {
        List<String> factors = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        int severity = 0;

        String normalized = weatherText == null ? "" : weatherText.toLowerCase(Locale.ROOT);
        boolean rainOrSnow = containsAny(normalized, "雨", "雪", "rain", "snow", "shower");
        boolean severeWeather = containsAny(normalized, "暴", "雷", "冰雹", "台风", "沙尘", "storm", "thunder", "hail", "typhoon");
        boolean fogOrHaze = containsAny(normalized, "雾", "霾", "fog", "haze");

        if (rainOrSnow) {
            severity = Math.max(severity, 1);
            factors.add("precipitation");
            suggestions.add("优先安排博物馆、展馆、商圈等室内景点，减少长距离步行。");
            hints.add("Precipitation; prefer indoor attractions and shorter transfers");
        }
        if (severeWeather) {
            severity = Math.max(severity, 2);
            factors.add("extreme_weather");
            suggestions.add("极端天气风险较高，户外景区建议延期或替换为室内方案。");
            hints.add("Extreme weather risk; postpone exposed outdoor stops");
        }
        if (fogOrHaze) {
            severity = Math.max(severity, 1);
            factors.add("visibility_or_air_quality");
            suggestions.add("能见度或空气质量可能影响观景，减少登高、远眺和高强度户外安排。");
            hints.add("Visibility or air quality risk; reduce scenic overlook and strenuous outdoor plans");
        }
        if (highTemperature != null && highTemperature >= HIGH_TEMPERATURE_C) {
            severity = Math.max(severity, highTemperature >= EXTREME_HIGH_TEMPERATURE_C ? 2 : 1);
            factors.add("high_temperature");
            suggestions.add("高温时段减少户外暴晒，上午/傍晚安排户外，中午转入室内或休息。");
            hints.add("High temperature; avoid exposed outdoor stops around midday");
        }
        if (lowTemperature != null && lowTemperature <= -5) {
            severity = Math.max(severity, 1);
            factors.add("low_temperature");
            suggestions.add("低温天气减少长时间户外停留，并预留保暖和室内休息点。");
            hints.add("Low temperature; shorten outdoor exposure");
        }
        if (windPower != null && windPower >= STRONG_WIND_LEVEL) {
            severity = Math.max(severity, windPower >= EXTREME_WIND_LEVEL ? 2 : 1);
            factors.add("strong_wind");
            suggestions.add("强风天气减少开放水域、山顶、观景台等暴露区域停留。");
            hints.add("Strong wind; reduce exposed outdoor stops");
        }

        if (suggestions.isEmpty()) {
            suggestions.add("天气稳定，户外景点可正常安排，保留防晒、补水和交通缓冲。");
            hints.add("Weather is stable; outdoor attractions are acceptable");
        }

        String level = severity >= 2 ? "HIGH" : severity == 1 ? "MEDIUM" : "LOW";
        return new WeatherRisk(
                level,
                severity >= 2 ? "HIGH" : "LOW",
                dedupe(factors),
                dedupe(suggestions),
                dedupe(hints),
                severity > 0,
                severity > 0,
                rainOrSnow || severeWeather,
                windPower != null && windPower >= STRONG_WIND_LEVEL);
    }

    private String buildSummary(Map<String, Object> weather, WeatherRisk risk) {
        String weatherText = firstNonBlank(weather.get("weather"), "未知天气");
        String temperature = firstNonBlank(weather.get("temperature"));
        String tempText = temperature.isBlank() ? "" : " " + temperature + "C";
        return weatherText + tempText + "; outdoorRisk=" + risk.level()
                + "; " + String.join("; ", risk.constraintHints());
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private int parsePositiveInt(Object value) {
        Integer parsed = parseMaxInteger(value);
        return parsed == null ? 0 : Math.max(0, parsed);
    }

    private Integer parseMaxInteger(Object value) {
        List<Integer> numbers = parseIntegers(value);
        return numbers.stream().max(Integer::compareTo).orElse(null);
    }

    private Integer parseMinInteger(Object value) {
        List<Integer> numbers = parseIntegers(value);
        return numbers.stream().min(Integer::compareTo).orElse(null);
    }

    private List<Integer> parseIntegers(Object value) {
        if (value == null) {
            return List.of();
        }
        Matcher matcher = INTEGER_PATTERN.matcher(value.toString());
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed numeric fragments.
            }
        }
        return numbers;
    }

    private Integer maxInteger(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.max(first, second);
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    private boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private List<String> dedupe(List<String> values) {
        return values.stream().distinct().toList();
    }

    private record WeatherRisk(String level,
                               String extremeWeatherRisk,
                               List<String> riskFactors,
                               List<String> suggestions,
                               List<String> constraintHints,
                               boolean indoorPreferred,
                               boolean shortWalkPreferred,
                               boolean avoidRain,
                               boolean avoidWind) {

        static WeatherRisk stable() {
            return new WeatherRisk("LOW", "LOW", List.of(), List.of(), List.of(),
                    false, false, false, false);
        }

        WeatherRisk merge(WeatherRisk other) {
            int currentSeverity = severity(level);
            int otherSeverity = severity(other.level);
            String mergedLevel = currentSeverity >= otherSeverity ? level : other.level;
            String mergedExtreme = "HIGH".equals(extremeWeatherRisk) || "HIGH".equals(other.extremeWeatherRisk)
                    ? "HIGH" : "LOW";
            List<String> mergedFactors = new ArrayList<>(riskFactors);
            mergedFactors.addAll(other.riskFactors);
            List<String> mergedSuggestions = new ArrayList<>(suggestions);
            mergedSuggestions.addAll(other.suggestions);
            List<String> mergedHints = new ArrayList<>(constraintHints);
            mergedHints.addAll(other.constraintHints);
            return new WeatherRisk(
                    mergedLevel,
                    mergedExtreme,
                    mergedFactors.stream().distinct().toList(),
                    mergedSuggestions.stream().distinct().toList(),
                    mergedHints.stream().distinct().toList(),
                    indoorPreferred || other.indoorPreferred,
                    shortWalkPreferred || other.shortWalkPreferred,
                    avoidRain || other.avoidRain,
                    avoidWind || other.avoidWind);
        }

        private static int severity(String level) {
            if ("HIGH".equals(level)) {
                return 2;
            }
            if ("MEDIUM".equals(level)) {
                return 1;
            }
            return 0;
        }
    }
}
