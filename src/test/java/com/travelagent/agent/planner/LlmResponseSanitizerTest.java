package com.travelagent.agent.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmResponseSanitizer}.
 *
 * Covers 10 input categories:
 * 1. Clean JSON
 * 2. Markdown-fenced JSON (```json ... ```)
 * 3. JSON with leading sentence
 * 4. JSON with trailing explanation
 * 5. Nested JSON object
 * 6. Empty string
 * 7. Null input
 * 8. Pure prose (no JSON)
 * 9. Fence with no closing backticks
 * 10. JSON with escaped quotes inside string values
 */
class LlmResponseSanitizerTest {

    // -----------------------------------------------------------------------
    // 1. Clean JSON — pass through
    // -----------------------------------------------------------------------
    @Test
    void cleanJson_returnedAsIs() {
        String input = "{\"attractionName\":\"兵马俑\",\"reason\":\"历史文化代表\"}";
        assertThat(LlmResponseSanitizer.sanitize(input)).isEqualTo(input);
    }

    // -----------------------------------------------------------------------
    // 2. Markdown-fenced JSON
    // -----------------------------------------------------------------------
    @Test
    void markdownFence_stripped() {
        String input = "```json\n{\"attractionName\":\"华清宫\"}\n```";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo("{\"attractionName\":\"华清宫\"}");
    }

    @Test
    void markdownFenceNoLang_stripped() {
        String input = "```\n{\"key\":\"value\"}\n```";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    // -----------------------------------------------------------------------
    // 3. JSON with leading sentence
    // -----------------------------------------------------------------------
    @Test
    void leadingSentence_jsonExtracted() {
        String input = "好的，以下是推荐的景点信息：{\"attractionName\":\"大雁塔\",\"reason\":\"唐文化地标\"}";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo("{\"attractionName\":\"大雁塔\",\"reason\":\"唐文化地标\"}");
    }

    // -----------------------------------------------------------------------
    // 4. JSON with trailing explanation
    // -----------------------------------------------------------------------
    @Test
    void trailingExplanation_jsonExtracted() {
        String input = "{\"attractionName\":\"钟楼\",\"reason\":\"明代建筑\"} 希望以上推荐对您有帮助！";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo("{\"attractionName\":\"钟楼\",\"reason\":\"明代建筑\"}");
    }

    // -----------------------------------------------------------------------
    // 5. Nested JSON object
    // -----------------------------------------------------------------------
    @Test
    void nestedJson_firstObjectExtracted() {
        String input = "{\"title\":\"西安3日游\",\"config\":{\"days\":3,\"mode\":\"driving\"},\"ok\":true}";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo(input); // full object returned
        assertThat(result).contains("\"config\":{");
    }

    // -----------------------------------------------------------------------
    // 6. Empty string
    // -----------------------------------------------------------------------
    @Test
    void emptyString_returnedAsEmpty() {
        assertThat(LlmResponseSanitizer.sanitize("")).isEqualTo("");
        assertThat(LlmResponseSanitizer.sanitize("   ")).isEqualTo("");
    }

    // -----------------------------------------------------------------------
    // 7. Null input
    // -----------------------------------------------------------------------
    @Test
    void nullInput_returnedAsEmpty() {
        assertThat(LlmResponseSanitizer.sanitize(null)).isEqualTo("");
    }

    // -----------------------------------------------------------------------
    // 8. Pure prose — no JSON at all
    // -----------------------------------------------------------------------
    @Test
    void pureProse_returnedTrimmed() {
        String input = "我推荐您游览兵马俑，这是世界著名的历史遗址。";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo(input); // fallback: return trimmed original
    }

    // -----------------------------------------------------------------------
    // 9. Fence with no closing backticks (malformed)
    // -----------------------------------------------------------------------
    @Test
    void unclosedFence_jsonStillExtracted() {
        String input = "```json\n{\"attractionName\":\"陕西历史博物馆\"}";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).isEqualTo("{\"attractionName\":\"陕西历史博物馆\"}");
    }

    // -----------------------------------------------------------------------
    // 10. JSON with escaped quotes inside string values
    // -----------------------------------------------------------------------
    @Test
    void escapedQuotesInValue_correctlyParsed() {
        String input = "{\"name\":\"\\\"秦始皇帝陵\\\"\",\"note\":\"重要\"}";
        String result = LlmResponseSanitizer.sanitize(input);
        assertThat(result).startsWith("{");
        assertThat(result).endsWith("}");
        assertThat(result).contains("秦始皇帝陵");
    }

    // -----------------------------------------------------------------------
    // extractFirstJsonObject — internal method tests
    // -----------------------------------------------------------------------
    @Test
    void extractFirstJsonObject_withPrefixAndSuffix() {
        String s = "前缀文字 {\"a\":1} 后缀文字";
        assertThat(LlmResponseSanitizer.extractFirstJsonObject(s)).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractFirstJsonObject_noJson_returnsNull() {
        assertThat(LlmResponseSanitizer.extractFirstJsonObject("没有 JSON 内容")).isNull();
    }
}
