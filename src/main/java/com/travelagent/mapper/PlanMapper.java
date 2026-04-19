package com.travelagent.mapper;

import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 中文注释：Mapper 接口，负责 Plan Mapper 相关的数据访问与持久化映射。
 */

@Mapper
public interface PlanMapper {

    int insertPlan(Plan plan);

    int insertStep(PlanStep step);

    int insertSteps(@Param("steps") List<PlanStep> steps);

    Plan findByTaskId(@Param("taskId") Long taskId);

    Plan findById(@Param("id") Long id);

    List<Plan> findByUserId(@Param("userId") Long userId);

    List<PlanStep> findStepsByPlanId(@Param("planId") Long planId);
}
