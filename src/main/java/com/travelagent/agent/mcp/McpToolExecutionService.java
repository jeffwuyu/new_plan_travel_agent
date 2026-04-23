package com.travelagent.agent.mcp;

import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.config.AgentMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class McpToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutionService.class);
    private static final Pattern LOCATION_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");

    private final AgentMcpProperties properties;
    private final McpToolInvoker toolInvoker;

    public McpToolExecutionService(AgentMcpProperties properties, McpToolInvoker toolInvoker) {
        this.properties = properties;
        this.toolInvoker = toolInvoker;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Map<String, Object> execute(String internalToolName, Map<String, Object> arguments) {
        if (!isEnabled()) {
            throw new McpException("MCP is disabled");
        }

        String mcpToolName = resolveToolName(internalToolName, arguments);
        if (mcpToolName == null || mcpToolName.isBlank()) {
            throw new McpException("No MCP tool mapping configured for internal tool: " + internalToolName);
        }

        Map<String, Object> mcpArguments = buildArguments(internalToolName, arguments);
        long startAt = System.currentTimeMillis();
        McpToolCallResult result = toolInvoker.callTool(mcpToolName, mcpArguments);
        long tookMs = System.currentTimeMillis() - startAt;

        log.info("[MCP] tools/call internalTool={} mcpTool={} tookMs={} args={}",
                internalToolName, mcpToolName, tookMs, mcpArguments);

        if (result.isError()) {
            throw new McpException("MCP tool returned isError=true for " + mcpToolName);
        }

        Map<String, Object> normalized = normalizeResult(internalToolName, mcpToolName, result);
        normalized.put("mcpTool", mcpToolName);
        normalized.put("mcpProvider", "amap-mcp");
        normalized.put("mcpFallback", false);
        return normalized;
    }

    private Map<String, Object> buildArguments(String internalToolName, Map<String, Object> arguments) {
        if (GeocodeTool.NAME.equals(internalToolName)) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("address", String.valueOf(arguments.getOrDefault("name", "")));
            Object region = arguments.get("region");
            if (region != null && !String.valueOf(region).isBlank()) {
                mapped.put("city", String.valueOf(region));
            }
            return mapped;
        }
        if (WeatherTool.NAME.equals(internalToolName)) {
            return Map.of("city", String.valueOf(arguments.getOrDefault("adcode", "")));
        }
        if (TrafficTimeTool.NAME.equals(internalToolName)) {
            return new LinkedHashMap<>(Map.of(
                    "origin", arguments.get("originLng") + "," + arguments.get("originLat"),
                    "destination", arguments.get("destLng") + "," + arguments.get("destLat")
            ));
        }
        return arguments == null ? Map.of() : arguments;
    }

    private String resolveToolName(String internalToolName, Map<String, Object> arguments) {
        if (TrafficTimeTool.NAME.equals(internalToolName)) {
            String routeMode = normalizeTravelMode(arguments == null ? null : arguments.get("travelMode"));
            String mapped = properties.getToolMapping().get(internalToolName + "." + routeMode);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        return properties.getToolMapping().get(internalToolName);
    }

    private Map<String, Object> normalizeResult(String internalToolName, String mcpToolName, McpToolCallResult result) {
        if (GeocodeTool.NAME.equals(internalToolName)) {
            return normalizeGeocode(result);
        }
        if (WeatherTool.NAME.equals(internalToolName)) {
            return normalizeWeather(result);
        }
        if (TrafficTimeTool.NAME.equals(internalToolName)) {
            return normalizeTraffic(mcpToolName, result);
        }
        return new LinkedHashMap<>(result.structuredContent());
    }

    private Map<String, Object> normalizeGeocode(McpToolCallResult result) {
        Map<String, Object> source = mergeResultMaps(result);
        Double lng = extractCoordinate(source, "lng", "longitude");
        Double lat = extractCoordinate(source, "lat", "latitude");
        if ((lng == null || lat == null) && source.get("location") != null) {
            double[] parsed = parseLocation(source.get("location").toString());
            if (!Double.isNaN(parsed[0])) {
                lng = lng == null ? parsed[0] : lng;
                lat = lat == null ? parsed[1] : lat;
            }
        }
        if (lng == null || lat == null) {
            throw new McpException("MCP geocode result missing coordinates");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("lat", lat);
        normalized.put("lng", lng);
        normalized.put("adcode", stringOrDefault(findFirstValue(source, "adcode"), ""));
        normalized.put("province", stringOrDefault(findFirstValue(source, "province"), ""));
        normalized.put("city", stringOrDefault(findFirstValue(source, "city"), ""));
        normalized.put("district", stringOrDefault(findFirstValue(source, "district"), ""));
        normalized.put("formattedAddress", stringOrDefault(findFirstValue(source, "formatted_address", "formattedAddress", "address"), ""));
        return normalized;
    }

    private Map<String, Object> normalizeWeather(McpToolCallResult result) {
        Map<String, Object> source = mergeResultMaps(result);
        Map<String, Object> normalized = new LinkedHashMap<>();
        Object forecastsObj = findFirstValue(source, "forecasts", "casts");
        if (forecastsObj instanceof List<?> forecasts
                && !forecasts.isEmpty()
                && forecasts.get(0) instanceof Map<?, ?> firstForecast) {
            normalized.put("weather", stringOrDefault(firstForecast.get("dayweather"), stringOrDefault(firstForecast.get("nightweather"), "Unknown")));
            normalized.put("temperature", stringOrDefault(firstForecast.get("daytemp"), stringOrDefault(firstForecast.get("nighttemp"), "")));
            normalized.put("windDirection", stringOrDefault(firstForecast.get("daywind"), stringOrDefault(firstForecast.get("nightwind"), "")));
            normalized.put("windPower", stringOrDefault(firstForecast.get("daypower"), stringOrDefault(firstForecast.get("nightpower"), "")));
            normalized.put("humidity", "");
            return normalized;
        }

        normalized.put("weather", stringOrDefault(findFirstValue(source, "weather"), "Unknown"));
        normalized.put("temperature", stringOrDefault(findFirstValue(source, "temperature", "temp"), ""));
        normalized.put("windDirection", stringOrDefault(findFirstValue(source, "winddirection", "windDirection"), ""));
        normalized.put("windPower", stringOrDefault(findFirstValue(source, "windpower", "windPower"), ""));
        normalized.put("humidity", stringOrDefault(findFirstValue(source, "humidity"), ""));
        return normalized;
    }

    private Map<String, Object> normalizeTraffic(String mcpToolName, McpToolCallResult result) {
        Map<String, Object> source = mergeResultMaps(result);
        Integer durationMin = extractDurationMinutes(source);
        Integer distanceMeters = extractInteger(findFirstValue(source, "distance", "distanceMeters"));

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("durationMin", durationMin == null ? 0 : durationMin);
        if (distanceMeters != null) {
            normalized.put("distanceMeters", distanceMeters);
        }
        normalized.put("routeMode", routeModeFromToolName(mcpToolName));
        return normalized;
    }

    private Map<String, Object> mergeResultMaps(McpToolCallResult result) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(result.rawResult());
        merged.putAll(result.structuredContent());
        if (result.content() != null && !result.content().isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (Map<String, Object> block : result.content()) {
                if (block == null) {
                    continue;
                }
                Object text = block.get("text");
                if (text != null) {
                    texts.add(text.toString());
                }
            }
            if (!texts.isEmpty()) {
                merged.put("text", String.join("\n", texts));
            }
        }
        return merged;
    }

    private Double extractCoordinate(Map<String, Object> source, String... keys) {
        Object value = findFirstValue(source, keys);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer extractDurationMinutes(Map<String, Object> source) {
        Object explicitMinutes = findFirstValue(source, "durationMin");
        if (explicitMinutes instanceof Number number) {
            return number.intValue();
        }
        if (explicitMinutes instanceof String text && !text.isBlank()) {
            try {
                return (int) Math.ceil(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        Object durationSeconds = findFirstValue(source, "duration");
        if (durationSeconds instanceof Number number) {
            return (int) Math.ceil(number.doubleValue() / 60.0);
        }
        if (durationSeconds instanceof String text && !text.isBlank()) {
            try {
                return (int) Math.ceil(Double.parseDouble(text.trim()) / 60.0);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer extractInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Math.round(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Object findFirstValue(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            Object direct = source.get(key);
            if (direct != null) {
                return direct;
            }
            Object deep = findRecursive(source, key);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    private Object findRecursive(Object current, String key) {
        if (current instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (key.equals(String.valueOf(entry.getKey())) && entry.getValue() != null) {
                    return entry.getValue();
                }
                Object deep = findRecursive(entry.getValue(), key);
                if (deep != null) {
                    return deep;
                }
            }
        } else if (current instanceof List<?> list) {
            for (Object item : list) {
                Object deep = findRecursive(item, key);
                if (deep != null) {
                    return deep;
                }
            }
        }
        return null;
    }

    private double[] parseLocation(String location) {
        Matcher matcher = LOCATION_PATTERN.matcher(location == null ? "" : location);
        if (!matcher.find()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        return new double[]{
                Double.parseDouble(matcher.group(1)),
                Double.parseDouble(matcher.group(2))
        };
    }

    private String routeModeFromToolName(String toolName) {
        if (toolName == null) {
            return "driving";
        }
        String lower = toolName.toLowerCase(Locale.ROOT);
        if (lower.contains("walking")) {
            return "walking";
        }
        if (lower.contains("transit")) {
            return "transit";
        }
        if (lower.contains("bicycling")) {
            return "bicycling";
        }
        return "driving";
    }

    private String normalizeTravelMode(Object travelMode) {
        if (travelMode == null) {
            return "driving";
        }
        String normalized = travelMode.toString().trim().toLowerCase(Locale.ROOT);
        if ("walking".equals(normalized) || "transit".equals(normalized) || "bicycling".equals(normalized)) {
            return normalized;
        }
        return "driving";
    }

    private String stringOrDefault(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? defaultValue : text;
    }
}
