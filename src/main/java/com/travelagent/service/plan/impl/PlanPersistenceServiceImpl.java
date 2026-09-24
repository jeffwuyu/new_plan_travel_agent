package com.travelagent.service.plan.impl;

import com.travelagent.mapper.PlanMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanAccommodation;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.service.plan.PlanPersistenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlanPersistenceServiceImpl implements PlanPersistenceService {

    @Autowired
    private PlanMapper planMapper;

    @Override
    @Transactional
    public void insertPlan(Plan plan) {
        planMapper.insertPlan(plan);
    }

    @Override
    @Transactional
    public void insertSteps(List<PlanStep> steps) {
        if (!steps.isEmpty()) {
            planMapper.insertSteps(steps);
        }
    }

    @Override
    @Transactional
    public void insertAccommodations(List<PlanAccommodation> accommodations) {
        if (!accommodations.isEmpty()) {
            planMapper.insertAccommodations(accommodations);
        }
    }

    @Override
    @Transactional
    public void updateAccommodationStatus(Long planId, String status, String failureReason) {
        planMapper.updateAccommodationStatus(planId, status, failureReason);
    }
}
