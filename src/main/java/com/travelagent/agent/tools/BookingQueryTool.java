package com.travelagent.agent.tools;

import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.web.WebSearchClient;
import com.travelagent.client.web.WebSearchQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BookingQueryTool implements AgentTool {

    public static final String NAME = "booking_query";

    private static final Pattern PRICE_PATTERN = Pattern.compile("(成人票|门票|票价|ticket|price)?\\s*[：:]?\\s*(¥|￥|CNY)?\\s*(\\d{1,4})(\\s*元|\\s*yuan)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SLOT_PATTERN = Pattern.compile(
            "(20\\d{2}-\\d{1,2}-\\d{1,2}|\\d{1,2}月\\d{1,2}日)?\\s*(\\d{1,2}:\\d{2}\\s*[-~至]\\s*\\d{1,2}:\\d{2})");

    private static final List<String> TRUSTED_HINTS = List.of(
            ".gov.cn", "gov.cn", "mct.gov.cn", "12301.cn", "12306.cn", "ctrip.com",
            "trip.com", "meituan.com", "dianping.com", "fliggy.com", "景区官网", "官方网站", "官方", "文化和旅游局"
    );
    private static final List<String> BOOKING_REQUIRED_HINTS = List.of(
            "需预约", "需要预约", "实名预约", "提前预约", "限流", "分时预约", "预约购票", "reservation required", "booking required"
    );
    private static final List<String> NO_BOOKING_HINTS = List.of(
            "无需预约", "免预约", "不需要预约", "no reservation required"
    );
    private static final List<String> AVAILABLE_HINTS = List.of(
            "可预约", "有票", "余票", "可购票", "available", "bookable"
    );
    private static final List<String> UNAVAILABLE_HINTS = List.of(
            "约满", "售罄", "无票", "不可预约", "暂停预约", "闭园", "关闭", "closed", "sold out", "unavailable"
    );

    @Autowired
    private WebSearchClient webSearchClient;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getSource() {
        return "web-search:booking";
    }

    @Override
    @IdempotentTool(ttl = "2h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        BookingQuery query = buildQuery(safeArguments);
        List<Map<String, Object>> rawResults = webSearchClient.search(toWebQuery(query));
        List<Map<String, Object>> sources = rankAndNormalize(rawResults);
        String corpus = sourceCorpus(sources);
        String availabilityStatus = resolveAvailabilityStatus(corpus);
        boolean bookingRequired = resolveBookingRequired(corpus, query.bookingRequired);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", !sources.isEmpty());
        result.put("attraction", query.attraction);
        result.put("city", query.city);
        result.put("date", query.date);
        result.put("dateRange", dateRange(query));
        result.put("bookingRequired", bookingRequired);
        result.put("availabilityStatus", availabilityStatus);
        result.put("reservableTimeSlots", extractTimeSlots(sources));
        result.put("ticketPriceSummary", buildTicketPriceSummary(sources));
        result.put("ticketTypeRecommendations", recommendTicketTypes(query, sources));
        result.put("officialEntry", resolveOfficialEntry(sources));
        result.put("bookingFlow", buildBookingFlow(bookingRequired));
        result.put("requiredUserPreparation", buildRequiredPreparation(bookingRequired));
        result.put("manualActionRequired", true);
        result.put("safetyPolicy", buildSafetyPolicy());
        result.put("alternatives", buildAlternatives(query, availabilityStatus));
        result.put("sources", sources);
        result.put("conflictDetected", hasConflict(corpus));
        result.put("uncertaintyNote", buildUncertaintyNote(sources, availabilityStatus));
        result.put("cacheTtl", "PT2H");
        result.put("source", getSource());
        result.put("queryTime", Instant.now().toString());
        return result;
    }

    private BookingQuery buildQuery(Map<String, Object> arguments) {
        return new BookingQuery(
                stringArg(arguments, "attraction", "attractionName", "poiName", "scenicSpot"),
                stringArg(arguments, "city", "destination", "region"),
                stringArg(arguments, "date", "travelDate", "startDate"),
                stringArg(arguments, "endDate"),
                stringArg(arguments, "ticketPreference", "preference"),
                stringArg(arguments, "query", "queryText"),
                boolArg(arguments.get("bookingRequired")),
                intArg(arguments.get("topK"), 5)
        );
    }

    private WebSearchQuery toWebQuery(BookingQuery query) {
        String queryText = query.queryText;
        if (queryText == null || queryText.isBlank()) {
            queryText = String.join(" ",
                    nullToEmpty(query.city),
                    nullToEmpty(query.attraction),
                    nullToEmpty(query.date),
                    "官方 预约 门票 票价 购票 入口 开放时间").trim();
        }
        return WebSearchQuery.builder()
                .attraction(query.attraction)
                .city(query.city)
                .date(query.date)
                .infoType("预约购票")
                .queryText(queryText)
                .topK(query.topK)
                .build();
    }

    private List<Map<String, Object>> rankAndNormalize(List<Map<String, Object>> rawResults) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> raw : rawResults == null ? List.<Map<String, Object>>of() : rawResults) {
            Map<String, Object> item = new LinkedHashMap<>(raw);
            int trustScore = trustScore(item);
            item.put("trustScore", trustScore);
            item.put("trustedSource", trustScore >= 70);
            item.putIfAbsent("sourceType", trustScore >= 90 ? "official" : trustScore >= 70 ? "high_trust" : "general_web");
            normalized.add(item);
        }
        normalized.sort(Comparator
                .comparingInt((Map<String, Object> item) -> ((Number) item.getOrDefault("trustScore", 0)).intValue())
                .reversed());
        return normalized;
    }

    private int trustScore(Map<String, Object> item) {
        String text = (stringValue(item.get("url")) + " " + stringValue(item.get("source")) + " "
                + stringValue(item.get("title"))).toLowerCase(Locale.ROOT);
        if (text.contains("景区官网") || text.contains("官方网站") || text.contains(".gov.cn") || text.contains("gov.cn")) {
            return 95;
        }
        for (String hint : TRUSTED_HINTS) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                return 80;
            }
        }
        return 50;
    }

    private boolean resolveBookingRequired(String corpus, Boolean fallback) {
        if (containsAny(corpus, NO_BOOKING_HINTS)) {
            return false;
        }
        if (containsAny(corpus, BOOKING_REQUIRED_HINTS)) {
            return true;
        }
        return Boolean.TRUE.equals(fallback);
    }

    private String resolveAvailabilityStatus(String corpus) {
        boolean available = containsAny(corpus, AVAILABLE_HINTS);
        boolean unavailable = containsAny(corpus, UNAVAILABLE_HINTS);
        if (available && unavailable) {
            return "CONFLICTED";
        }
        if (unavailable) {
            return "UNAVAILABLE";
        }
        if (available) {
            return "AVAILABLE";
        }
        return "UNKNOWN";
    }

    private Map<String, Object> resolveOfficialEntry(List<Map<String, Object>> sources) {
        for (Map<String, Object> source : sources) {
            if (intValue(source.get("trustScore")) >= 70 && !stringValue(source.get("url")).isBlank()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("title", source.get("title"));
                entry.put("url", source.get("url"));
                entry.put("source", source.getOrDefault("source", source.get("sourceType")));
                entry.put("sourceType", source.get("sourceType"));
                return entry;
            }
        }
        return Map.of();
    }

    private Map<String, Object> buildTicketPriceSummary(List<Map<String, Object>> sources) {
        List<String> prices = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Matcher matcher = PRICE_PATTERN.matcher(sourceText(source));
            while (matcher.find() && prices.size() < 5) {
                String label = matcher.group(1) == null ? "票价" : matcher.group(1).trim();
                String amount = matcher.group(3);
                String price = label + " " + amount + "元";
                if (!prices.contains(price)) {
                    prices.add(price);
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("prices", prices);
        summary.put("summary", prices.isEmpty()
                ? "未检索到明确票价，需以官方预约或购票入口实时展示为准。"
                : "检索到参考票价：" + String.join("；", prices) + "。");
        summary.put("priceSourceRequired", true);
        return summary;
    }

    private List<Map<String, Object>> recommendTicketTypes(BookingQuery query, List<Map<String, Object>> sources) {
        String corpus = sourceCorpus(sources) + " " + nullToEmpty(query.ticketPreference);
        List<Map<String, Object>> recommendations = new ArrayList<>();
        recommendations.add(ticketType("成人票", "普通成人游客优先核对成人票或全价票。"));
        if (containsAny(corpus, List.of("学生", "student", "儿童", "老人", "老年", "优惠", "discount"))) {
            recommendations.add(ticketType("优惠票", "学生、儿童、老人等优惠票通常需要现场或线上核验证件，用户需自行填写实名信息。"));
        } else {
            recommendations.add(ticketType("优惠/特殊人群票", "如同行人包含学生、儿童、老人或军人，请在官方入口核对优惠资格。"));
        }
        if (containsAny(corpus, List.of("讲解", "导览", "联票", "套票", "family", "亲子"))) {
            recommendations.add(ticketType("套票/讲解票", "有讲解、联票或亲子需求时，优先选择官方说明中覆盖对应服务的票种。"));
        }
        return recommendations;
    }

    private Map<String, Object> ticketType(String type, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("reason", reason);
        item.put("requiresManualIdentityInput", true);
        return item;
    }

    private List<String> extractTimeSlots(List<Map<String, Object>> sources) {
        List<String> slots = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Matcher matcher = SLOT_PATTERN.matcher(sourceText(source));
            while (matcher.find() && slots.size() < 8) {
                String date = matcher.group(1) == null ? "" : matcher.group(1).trim() + " ";
                String slot = date + matcher.group(2).replace("至", "-").replace("~", "-").trim();
                if (!slots.contains(slot)) {
                    slots.add(slot);
                }
            }
        }
        return slots;
    }

    private List<String> buildBookingFlow(boolean bookingRequired) {
        if (!bookingRequired) {
            return List.of(
                    "核对官方开放时间、票价和入园规则。",
                    "如现场购票可用，仍建议出行前再次确认限流和闭园公告。",
                    "涉及实名信息、验证码或支付时由用户在官方入口手动完成。"
            );
        }
        return List.of(
                "打开官方预约或购票入口，核对游玩日期和可预约时间段。",
                "选择匹配同行人的票种，确认退改、入园证件和检票规则。",
                "由用户手动填写实名信息、手机号、证件号和验证码。",
                "由用户自行确认订单并完成支付，系统只保留查询摘要和提醒。"
        );
    }

    private List<String> buildRequiredPreparation(boolean bookingRequired) {
        List<String> items = new ArrayList<>();
        items.add("出行日期和计划入园时间段");
        items.add("同行人数和适用票种");
        if (bookingRequired) {
            items.add("实名预约所需证件信息，由用户在官方入口手动填写");
            items.add("手机号、验证码和支付方式，由用户自行处理");
        }
        items.add("官方退改、闭园、限流和入园证件规则");
        return items;
    }

    private Map<String, Object> buildSafetyPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("manualConfirmationRequired", true);
        policy.put("autoFillSensitiveInfo", false);
        policy.put("autoSubmitOrder", false);
        policy.put("autoPayment", false);
        policy.put("autoCancelOrRefund", false);
        policy.put("sensitiveFields", List.of(
                "真实姓名", "手机号", "身份证号", "护照号", "门票实名预约信息", "第三方平台登录信息", "验证码", "支付信息"
        ));
        policy.put("prohibitedActions", List.of(
                "自动填写实名信息", "自动提交订单", "自动支付", "自动取消订单", "自动退款", "保存身份证号或验证码"
        ));
        return policy;
    }

    private Map<String, Object> buildAlternatives(BookingQuery query, String availabilityStatus) {
        Map<String, Object> alternatives = new LinkedHashMap<>();
        boolean needsAlternative = "UNAVAILABLE".equals(availabilityStatus) || "CONFLICTED".equals(availabilityStatus);
        alternatives.put("needed", needsAlternative);
        alternatives.put("alternativeDates", needsAlternative
                ? List.of("尝试同日其他时段", "尝试前后相邻日期", "避开周末和节假日高峰")
                : List.of());
        alternatives.put("alternativeAttractions", needsAlternative
                ? List.of("选择同城相似主题景点", "保留该景点但调整到其他日期", "替换为无需预约或现场购票景点")
                : List.of());
        alternatives.put("manualReservationEntry", query.attraction == null || query.attraction.isBlank()
                ? "请进入景区官方渠道或官方小程序人工核对。"
                : "请进入" + query.attraction + "官方渠道或官方小程序人工核对。");
        return alternatives;
    }

    private String buildUncertaintyNote(List<Map<String, Object>> sources, String availabilityStatus) {
        if (sources.isEmpty()) {
            return "未检索到可用票务来源，建议用户进入景区官网、官方公众号或可信票务平台人工确认。";
        }
        if ("CONFLICTED".equals(availabilityStatus)) {
            return "不同来源的可预约状态可能冲突，已优先展示官方或高可信来源，出行前必须再次确认。";
        }
        return "预约状态、余票、票价和开放时间变化较快，最终以官方预约或购票入口实时展示为准。";
    }

    private boolean hasConflict(String corpus) {
        return containsAny(corpus, AVAILABLE_HINTS) && containsAny(corpus, UNAVAILABLE_HINTS);
    }

    private Map<String, Object> dateRange(BookingQuery query) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (query.date != null && !query.date.isBlank()) {
            range.put("startDate", query.date);
        }
        if (query.endDate != null && !query.endDate.isBlank()) {
            range.put("endDate", query.endDate);
        }
        return range;
    }

    private String sourceCorpus(List<Map<String, Object>> sources) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> source : sources) {
            sb.append(sourceText(source)).append(' ');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private String sourceText(Map<String, Object> source) {
        return stringValue(source.get("title")) + " "
                + stringValue(source.get("snippet")) + " "
                + stringValue(source.get("summary")) + " "
                + stringValue(source.get("source"));
    }

    private boolean containsAny(String text, List<String> tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String stringArg(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private Boolean boolArg(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return null;
    }

    private int intArg(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record BookingQuery(String attraction,
                                String city,
                                String date,
                                String endDate,
                                String ticketPreference,
                                String queryText,
                                Boolean bookingRequired,
                                int topK) {
    }
}
