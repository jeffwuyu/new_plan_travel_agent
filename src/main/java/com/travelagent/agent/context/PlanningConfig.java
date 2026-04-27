package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanningConfig {

    public static final LocalTime DEFAULT_FULL_DAY_START = LocalTime.of(7, 0);
    public static final LocalTime DEFAULT_FULL_DAY_END = LocalTime.of(21, 0);

    private int totalDays = 1;
    private int attractionsPerDay = 3;
    private int dynamicTargetSteps = 3;
    private List<String> preferenceKeywords;
    private String travelMode = "driving";
    private String startLocationQuery;
    private String endLocationQuery;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalTime fullDayStartTime;
    private LocalTime fullDayEndTime;
    private int defaultVisitDurationMin = 120;
    private int destinationBufferMin = 30;
    private int minContinueBudgetMin = 90;

    /**
     * 初始化PlanningConfig 实例。
     * @param totalDays t ot al Da ys 参数
     * @param preferenceKeywords p re fe re nc eK ey wo rd s 参数
     * @param travelMode 出行方式
     */
    public PlanningConfig(int totalDays, List<String> preferenceKeywords, String travelMode) {
        this.totalDays = totalDays;
        this.preferenceKeywords = preferenceKeywords;
        this.travelMode = travelMode;
    }

    /**
     * 初始化PlanningConfig 实例。
     * @param totalDays t ot al Da ys 参数
     * @param attractionsPerDay a tt ra ct io ns Pe rD ay 参数
     * @param preferenceKeywords p re fe re nc eK ey wo rd s 参数
     * @param travelMode 出行方式
     */
    public PlanningConfig(int totalDays, int attractionsPerDay, List<String> preferenceKeywords, String travelMode) {
        this.totalDays = totalDays;
        this.attractionsPerDay = attractionsPerDay;
        this.dynamicTargetSteps = Math.max(1, totalDays * Math.max(1, attractionsPerDay));
        this.preferenceKeywords = preferenceKeywords;
        this.travelMode = travelMode;
    }

    /**
     * 处理totalSteps。
     * @return 返回处理结果。
     */
    public int totalSteps() {
        return Math.max(1, dynamicTargetSteps);
    }

    /**
     * 解析并确定fulldaystarttime。
     * @return 返回处理结果。
     */
    public LocalTime resolveFullDayStartTime() {
        return fullDayStartTime != null ? fullDayStartTime : DEFAULT_FULL_DAY_START;
    }

    /**
     * 解析并确定fulldayendtime。
     * @return 返回处理结果。
     */
    public LocalTime resolveFullDayEndTime() {
        return fullDayEndTime != null ? fullDayEndTime : DEFAULT_FULL_DAY_END;
    }

    /**
     * 判断是否具备customfulldaywindow。
     * @return 是否满足当前条件。
     */
    public boolean hasCustomFullDayWindow() {
        return fullDayStartTime != null && fullDayEndTime != null;
    }
}
