package com.travelagent.agent.validation;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.FinalSummaryResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HallucinationDetector {

    private static final Pattern MOBILE_PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?86[-\\s]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern LANDLINE_PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?86[-\\s]?)?0\\d{2,3}[-\\s]?\\d{7,8}(?:[-\\s]\\d{1,6})?(?!\\d)");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s，。；;、)）]+");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?i)(?:[¥￥]\\s*\\d+(?:\\.\\d+)?|(?:cny|rmb|人民币)\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?\\s*元)");
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d\\s*(?:-|~|至|到)\\s*(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)");
    private static final Pattern OPENING_TIME_PATTERN = Pattern.compile("(?:开放时间|营业时间|入园时间|预约时间|可预约时间|开放时段)[：: ]*[^。；;\\n]{2,40}");
    private static final Pattern TRAFFIC_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:分钟|min|公里|km|米|m)\\s*(?:车程|步行|骑行|公交|交通|转场|路程|距离|到达)");
    private static final Pattern WEATHER_PATTERN = Pattern.compile("(?:晴|多云|阴天|小雨|中雨|大雨|暴雨|阵雨|雷雨|小雪|中雪|大雪|雾|大风|强风|高温|低温|\\d{1,2}\\s*(?:°C|℃))");
    private static final Pattern BOOKING_PATTERN = Pattern.compile("(?:需预约|需要预约|免预约|无需预约|有票|余票|售罄|已售罄|可预约|不可预约|可购票|购票入口|门票)");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?:地址|位于|坐落于)[：: ]*[^。；;\\n]{4,60}(?:路|街|巷|号|区|县|市|省|广场|中心|景区|公园)");

    private final HallucinationDetectionProperties properties;

    public HallucinationDetector(HallucinationDetectionProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties == null || properties.isEnabled();
    }

    public int maxRetries() {
        return properties == null ? 3 : properties.getMaxRetries();
    }

    public HallucinationDetectionResult validateFinalSummary(FinalSummaryResult summary, TaskCheckpoint checkpoint) {
        if (!isEnabled() || summary == null) {
            return HallucinationDetectionResult.valid();
        }

        ToolEvidenceIndex evidence = ToolEvidenceIndex.from(checkpoint);
        List<HallucinationDetectionIssue> issues = new ArrayList<>();
        inspectText("title", summary.title(), evidence, issues);
        inspectText("summary", summary.summary(), evidence, issues);
        for (FinalSummaryResult.StepSummary step : summary.steps() == null
                ? List.<FinalSummaryResult.StepSummary>of()
                : summary.steps()) {
            inspectText("steps[" + step.stepOrder() + "].llmDescription", step.llmDescription(), evidence, issues);
        }
        if (issues.isEmpty()) {
            return HallucinationDetectionResult.valid();
        }
        return new HallucinationDetectionResult(false, Instant.now(), issues, buildRetryFeedback(issues));
    }

    private void inspectText(String path,
                             String text,
                             ToolEvidenceIndex evidence,
                             List<HallucinationDetectionIssue> issues) {
        if (text == null || text.isBlank()) {
            return;
        }
        collectMatches("phone", ToolEvidenceIndex.ClaimType.PHONE, MOBILE_PHONE_PATTERN, path, text, evidence, issues);
        collectMatches("phone", ToolEvidenceIndex.ClaimType.PHONE, LANDLINE_PHONE_PATTERN, path, text, evidence, issues);
        collectMatches("url", ToolEvidenceIndex.ClaimType.URL, URL_PATTERN, path, text, evidence, issues);
        collectMatches("price", ToolEvidenceIndex.ClaimType.PRICE, PRICE_PATTERN, path, text, evidence, issues);
        collectMatches("time", ToolEvidenceIndex.ClaimType.TIME, TIME_RANGE_PATTERN, path, text, evidence, issues);
        collectMatches("time", ToolEvidenceIndex.ClaimType.TIME, OPENING_TIME_PATTERN, path, text, evidence, issues);
        collectMatches("weather", ToolEvidenceIndex.ClaimType.WEATHER, WEATHER_PATTERN, path, text, evidence, issues);
        collectMatches("traffic", ToolEvidenceIndex.ClaimType.TRAFFIC, TRAFFIC_PATTERN, path, text, evidence, issues);
        collectMatches("booking", ToolEvidenceIndex.ClaimType.BOOKING, BOOKING_PATTERN, path, text, evidence, issues);
        collectMatches("address", ToolEvidenceIndex.ClaimType.ADDRESS, ADDRESS_PATTERN, path, text, evidence, issues);
    }

    private void collectMatches(String claimType,
                                ToolEvidenceIndex.ClaimType evidenceType,
                                Pattern pattern,
                                String path,
                                String text,
                                ToolEvidenceIndex evidence,
                                List<HallucinationDetectionIssue> issues) {
        if (properties != null && !properties.includes(claimType)) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String claim = matcher.group().trim();
            if (claim.isBlank() || evidence.supports(evidenceType, claim)) {
                continue;
            }
            issues.add(new HallucinationDetectionIssue(
                    "unsupported_" + claimType + "_claim",
                    "ERROR",
                    path,
                    claim,
                    "LLM output contains a " + claimType + " claim that is not supported by consumable tool results.",
                    Map.of("claimType", claimType)
            ));
        }
    }

    private String buildRetryFeedback(List<HallucinationDetectionIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hallucination detection failed. Rewrite the JSON using only facts present in Tool Result evidence.\n");
        sb.append("If a fact is not in tool evidence, remove it or state that the tool did not return it and it needs official confirmation.\n");
        int index = 1;
        for (HallucinationDetectionIssue issue : issues) {
            sb.append(index++)
                    .append(". Unsupported ")
                    .append(String.valueOf(issue.details().getOrDefault("claimType", "fact")).toLowerCase(Locale.ROOT))
                    .append(" claim at ")
                    .append(issue.targetPath())
                    .append(": ")
                    .append(issue.claimedValue())
                    .append(".\n");
            if (index > 8) {
                sb.append("Only the first 7 unsupported claims are listed. Remove all unsupported facts.\n");
                break;
            }
        }
        return sb.toString().trim();
    }
}
