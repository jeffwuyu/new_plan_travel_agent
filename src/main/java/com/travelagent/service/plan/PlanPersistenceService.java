package com.travelagent.service.plan;

import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanAccommodation;
import com.travelagent.model.entity.PlanStep;

import java.util.List;

/**
 * 封装 plans / plan_steps 表的写操作，使 AgentServiceImpl 不直接依赖 PlanMapper。
 */
public interface PlanPersistenceService {

    /**
     * 插入 Plan 头记录。MyBatis useGeneratedKeys 会将生成的主键回填到 plan.getId()。
     */
    void insertPlan(Plan plan);

    /**
     * 批量插入 PlanStep。若列表为空则为 no-op。
     */
    void insertSteps(List<PlanStep> steps);

    void insertAccommodations(List<PlanAccommodation> accommodations);

    void updateAccommodationStatus(Long planId, String status, String failureReason);
}
