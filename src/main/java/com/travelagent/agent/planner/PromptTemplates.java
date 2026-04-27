package com.travelagent.agent.planner;

/**
 * Central repository for all LLM prompt templates used by {@link MarkovPlanner}.
 *
 * <p>All templates are named static constants or static factory methods.
 * Keeping them here makes prompt changes reviewable as clean diffs and
 * keeps {@link MarkovPlanner} focused on orchestration logic.
 *
 * <p>Template variables use {@code {placeholder}} convention; callers use
 * {@link String#format} or {@code replace()} to inject values.
 */
public final class PromptTemplates {

    /**
     * 初始化PromptTemplates 实例。
     */
    private PromptTemplates() {}

    // -----------------------------------------------------------------------
    // Per-step planning (attraction selection)
    // -----------------------------------------------------------------------

    public static final String STEP_DAY_START =
            "请为第%d天推荐第%d个（总第%d/%d个）景点。\n" +
            "目的地：%s，出行方式：%s。\n" +
            "这是新一天的第一个景点，请选择与前一天景点不同的区域作为全天的地理锚点，避免重复同一地带。\n" +
            "上一天最后一站：%s。";

    public static final String STEP_DAY1_START =
            "请为第%d天推荐第%d个（总第%d/%d个）景点。\n" +
            "目的地：%s，出行方式：%s。\n" +
            "这是行程第一站，请选择该区域最具代表性的核心景点作为全天的地理锚点。";

    public static final String STEP_WITHIN_DAY =
            "请为第%d天推荐第%d个（总第%d/%d个）景点。\n" +
            "目的地：%s，出行方式：%s。\n" +
            "请在上一个景点「%s」（纬度=%.6f，经度=%.6f）15km 以内选择，保持当天行程的地理连贯性。\n" +
            "不要推荐已规划过的景点。";

    // -----------------------------------------------------------------------
    // Final summary generation
    // -----------------------------------------------------------------------

    public static final String FINAL_SUMMARY_SYSTEM =
            "你是一名专业旅游文案撰写人。用户刚完成了一次前往 %s 的 %d 天旅行规划。\n" +
            "用户出行意图：%s\n" +
            "出行方式：%s\n" +
            "偏好标签：%s\n\n" +
            "请根据以下完成的景点列表生成结构化的旅行摘要 JSON。\n\n" +
            "## 输出要求\n" +
            "严格按照以下 JSON Schema 返回，不要输出任何其他内容：\n" +
            "{\n" +
            "  \"title\": \"旅行标题\",\n" +
            "  \"summary\": \"行程总览段落\",\n" +
            "  \"steps\": [\n" +
            "    {\"stepOrder\": 0, \"estimatedDurationMin\": 120, \"llmDescription\": \"参观建议\"}\n" +
            "  ]\n" +
            "}\n\n" +
            "## 标题规则\n" +
            "- 格式必须为：{城市}{N}日{主题特色}游\n" +
            "- 示例：「西安3日历史文化游」「成都5日美食休闲游」「张家界4日自然探险游」\n" +
            "- 主题特色从偏好标签中提炼；无偏好标签时使用「精华」\n" +
            "- 不得包含英文\n\n" +
            "## 摘要规则\n" +
            "- 必须是 3-5 句中文，总字数不少于 80 字\n" +
            "- 覆盖以下三个维度：①整体行程节奏与结构  ②跨天的亮点景点与特色  ③旅行基调与体验感\n" +
            "- 禁止以「本次旅行」「此次行程」开头\n" +
            "- 禁止原文复述用户意图\n" +
            "- 示例：「这是一段以秦汉唐文明为主线的深度文化之旅……」\n\n" +
            "## 每步 llmDescription 规则\n" +
            "- 必须是 2-3 句中文实用建议，字数 30-150 字\n" +
            "- 第一句：最佳参观时间或建议游览时长\n" +
            "- 第二句：一条可操作 tip（如：提前网上预约门票 / 携带防晒用品 / 建议早上到达避开人流）\n" +
            "- 第三句：结合偏好标签「%s」说明该景点的独特价值\n" +
            "- 禁止使用「是著名景点」「历史悠久」「不可错过」等泛化套话\n\n" +
            "## estimatedDurationMin 规则\n" +
            "按景点规模合理估算，参考区间如下（非硬性规定，根据实际调整）：\n" +
            "- 小型寺庙、历史街巷、观景台：45-90 分钟\n" +
            "- 城市公园、植物园、中小型博物馆：90-150 分钟\n" +
            "- 大型国家博物馆、故宫类宫殿、主题公园：150-240 分钟\n" +
            "- 多景区联合（如黄山、西湖全环）：180-360 分钟\n" +
            "当前出行方式：%s（步行游客通常需要更多时间）\n" +
            "同一规划中各景点时长必须有所差异，禁止所有步骤分配相同时长。\n" +
            "时长范围：45-360 分钟。";

    public static final String FINAL_SUMMARY_USER_PREFIX =
            "已完成的景点列表：\n";

    public static final String FINAL_SUMMARY_USER_SUFFIX =
            "\n请按照上述规则生成 JSON 摘要。";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * 构建finalsummarysystem。
     * @param region 区域信息
     * @param totalDays t ot al Da ys 参数
     * @param userIntent u se rI nt en t 参数
     * @param travelMode 出行方式
     * @param preferenceKeywords p re fe re nc eK ey wo rd s 参数
     * @return 返回处理结果。
     */
    public static String buildFinalSummarySystem(
            String region, int totalDays, String userIntent,
            String travelMode, String preferenceKeywords) {
        String keywords = (preferenceKeywords == null || preferenceKeywords.isBlank())
                ? "暂无" : preferenceKeywords;
        String mode = (travelMode == null || travelMode.isBlank()) ? "驾车" : travelMode;
        return String.format(FINAL_SUMMARY_SYSTEM,
                region, totalDays, userIntent, mode, keywords, keywords, mode);
    }
}
