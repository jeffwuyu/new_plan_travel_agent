package com.travelagent.agent.planner;

/**
 * Sanitizes raw LLM responses to extract the first valid JSON object.
 *
 * <p>More robust than regex-based fence stripping: uses a bracket-depth scanner
 * that tolerates leading prose, trailing explanations, and markdown code fences.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Strip common markdown fences (```json ... ```) if present</li>
 *   <li>Scan character-by-character for the first '{' to find the JSON start</li>
 *   <li>Track bracket depth; when depth returns to 0 after opening, capture the substring</li>
 *   <li>If no valid JSON object is found, return the original trimmed string unchanged</li>
 * </ol>
 *
 * <p>This preserves existing fallback behaviour: if the LLM returns no JSON at all,
 * the caller's existing catch-block still handles it gracefully.
 */
public final class LlmResponseSanitizer {

    /**
     * 初始化LlmResponseSanitizer 实例。
     */
    private LlmResponseSanitizer() {}

    /**
     * 处理sanitize。
     * @param raw r aw 参数
     * @return 返回处理结果。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw.trim();
        }

        // Step 1: strip markdown fences
        String cleaned = stripMarkdownFences(raw.trim());

        // Step 2: find first complete JSON object using bracket-depth scan
        String extracted = extractFirstJsonObject(cleaned);
        return extracted != null ? extracted : cleaned;
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * 处理stripMarkdownFences。
     * @param s s 参数
     * @return 返回处理结果。
     */
    private static String stripMarkdownFences(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        // Remove opening fence line (e.g. "```json\n" or "```\n")
        int newline = s.indexOf('\n');
        if (newline < 0) {
            return s.replace("```", "").trim();
        }
        String body = s.substring(newline + 1);
        // Remove closing fence
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    /**
     * 提取firstjsonobject。
     * @param s s 参数
     * @return 返回处理结果。
     */
    static String extractFirstJsonObject(String s) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
