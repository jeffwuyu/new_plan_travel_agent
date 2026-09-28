package com.travelagent.service.accommodation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.util.JsonUtil;
import com.travelagent.util.RedisUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "accommodation", name = "provider", havingValue = "ctrip", matchIfMissing = true)
public class CtripAccommodationProvider implements AccommodationProvider {

    private static final Logger log = LoggerFactory.getLogger(CtripAccommodationProvider.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Duration CACHE_TTL = Duration.ofMinutes(20);

    private final OkHttpClient okHttpClient;
    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;

    @Value("${ctrip.api.base-url:}")
    private String baseUrl;

    @Value("${ctrip.api.app-id:}")
    private String appId;

    @Value("${ctrip.api.secret:}")
    private String secret;

    @Value("${ctrip.api.timeout-ms:10000}")
    private int timeoutMs;

    public CtripAccommodationProvider(OkHttpClient okHttpClient, RedisUtil redisUtil, JsonUtil jsonUtil) {
        this.okHttpClient = okHttpClient;
        this.redisUtil = redisUtil;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public List<AccommodationQuote> searchAvailability(AccommodationSearchRequest request) {
        ensureConfigured();
        String cacheKey = buildCacheKey(request);
        List<AccommodationQuote> cached = readCached(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<AccommodationQuote> quotes = fetchAvailability(request);
        try {
            redisUtil.setString(cacheKey, jsonUtil.toJson(quotes), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[CtripAccommodationProvider] cache write failed key={}: {}", cacheKey, e.getMessage());
        }
        return quotes;
    }

    private void ensureConfigured() {
        if (baseUrl == null || baseUrl.isBlank() || appId == null || appId.isBlank() || secret == null || secret.isBlank()) {
            throw new AccommodationAvailabilityException("OTA_NOT_CONFIGURED",
                    "Ctrip accommodation API is not configured");
        }
    }

    private List<AccommodationQuote> readCached(String cacheKey) {
        try {
            String cached = redisUtil.getString(cacheKey);
            if (cached != null) {
                return jsonUtil.fromJson(cached, new TypeReference<List<AccommodationQuote>>() {});
            }
        } catch (Exception e) {
            log.warn("[CtripAccommodationProvider] cache read failed key={}: {}", cacheKey, e.getMessage());
        }
        return null;
    }

    private List<AccommodationQuote> fetchAvailability(AccommodationSearchRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", appId);
        payload.put("cityName", firstNonBlank(request.getCityName(), request.getRegion()));
        payload.put("districtName", request.getDistrictName());
        payload.put("adcode", request.getAdcode());
        payload.put("checkInDate", request.getCheckInDate());
        payload.put("checkOutDate", request.getCheckOutDate());
        payload.put("adultCount", request.getAdultCount());
        payload.put("roomCount", request.getRoomCount());
        payload.put("accommodationTypes", request.getAccommodationTypes());
        payload.put("maxPricePerNightYuan", request.getLodgingBudgetPerNightYuan());
        payload.put("signature", secret);

        Request httpRequest = new Request.Builder()
                .url(baseUrl)
                .post(RequestBody.create(jsonUtil.toJson(payload), JSON))
                .build();
        OkHttpClient callClient = okHttpClient.newBuilder()
                .callTimeout(Duration.ofMillis(timeoutMs))
                .build();
        try (Response response = callClient.newCall(httpRequest).execute()) {
            if (response.code() == 429) {
                throw new AccommodationAvailabilityException("OTA_RATE_LIMITED", "Ctrip accommodation API rate limited");
            }
            if (!response.isSuccessful()) {
                throw new AccommodationAvailabilityException("OTA_HTTP_ERROR",
                        "Ctrip accommodation API returned HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new AccommodationAvailabilityException("OTA_EMPTY_RESPONSE",
                        "Ctrip accommodation API returned empty response");
            }
            return parseQuotes(response.body().string());
        } catch (AccommodationAvailabilityException e) {
            throw e;
        } catch (IOException e) {
            throw new AccommodationAvailabilityException("OTA_TIMEOUT_OR_IO",
                    "Ctrip accommodation API request failed within timeout " + timeoutMs + "ms", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<AccommodationQuote> parseQuotes(String body) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
            Object hotelsObj = root.get("hotels");
            if (hotelsObj == null) {
                hotelsObj = root.get("data");
            }
            if (!(hotelsObj instanceof List<?> hotels)) {
                throw new AccommodationAvailabilityException("OTA_NO_PRICE",
                        "Ctrip accommodation API returned no hotel price list");
            }
            LocalDateTime fetchedAt = LocalDateTime.now();
            return hotels.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> toQuote((Map<String, Object>) item, fetchedAt))
                    .filter(quote -> Boolean.TRUE.equals(quote.getAvailable()) && quote.getPricePerNightYuan() != null)
                    .toList();
        } catch (AccommodationAvailabilityException e) {
            throw e;
        } catch (Exception e) {
            throw new AccommodationAvailabilityException("OTA_PARSE_ERROR",
                    "Failed to parse Ctrip accommodation response", e);
        }
    }

    private AccommodationQuote toQuote(Map<String, Object> item, LocalDateTime fetchedAt) {
        AccommodationQuote quote = new AccommodationQuote();
        quote.setProvider("ctrip");
        quote.setProviderHotelId(stringValue(firstPresent(item, "hotelId", "providerHotelId", "id")));
        quote.setName(stringValue(firstPresent(item, "name", "hotelName")));
        quote.setType(stringValue(firstPresent(item, "type", "hotelType", "category")));
        quote.setAddress(stringValue(item.get("address")));
        quote.setLatitude(doubleValue(firstPresent(item, "latitude", "lat")));
        quote.setLongitude(doubleValue(firstPresent(item, "longitude", "lng")));
        quote.setRating(decimalValue(firstPresent(item, "rating", "score")));
        quote.setReviewCount(intValue(firstPresent(item, "reviewCount", "commentCount")));
        quote.setPricePerNightYuan(decimalValue(firstPresent(item, "pricePerNightYuan", "price", "lowestPrice")));
        quote.setCurrency(firstNonBlank(stringValue(item.get("currency")), "CNY"));
        quote.setAvailable(booleanValue(firstPresent(item, "available", "isAvailable"), true));
        quote.setCancellationPolicy(stringValue(item.get("cancellationPolicy")));
        quote.setPriceFetchedAt(fetchedAt);
        return quote;
    }

    private String buildCacheKey(AccommodationSearchRequest request) {
        return "ota:ctrip:" + firstNonBlank(request.getCityName(), request.getRegion(), "-")
                + ":" + request.getCheckInDate()
                + ":" + request.getCheckOutDate()
                + ":" + request.getAdultCount()
                + ":" + request.getRoomCount()
                + ":" + request.getLodgingBudgetPerNightYuan()
                + ":" + (request.getAccommodationTypes() == null ? "-" : String.join(",", request.getAccommodationTypes()));
    }

    private Object firstPresent(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            if (item.get(key) != null) {
                return item.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double doubleValue(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String normalized = value.toString().trim().toLowerCase();
        return switch (normalized) {
            case "1", "true", "yes", "y" -> true;
            case "0", "false", "no", "n" -> false;
            default -> defaultValue;
        };
    }
}
