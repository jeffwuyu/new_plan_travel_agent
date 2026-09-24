package com.travelagent.agent.planner;

import com.travelagent.agent.requirements.TravelConstraints;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
public class TravelPlan {

    private String planId = UUID.randomUUID().toString();
    private int version = 1;
    private String goal;
    private TravelConstraints constraints;
    private List<TravelPlanTask> tasks = new ArrayList<>();
    private Map<String, List<String>> dependencies = new LinkedHashMap<>();
    private PlanTaskStatus status = PlanTaskStatus.PENDING;
    private String changeReason = "initial plan";
    private List<TravelPlanChangeRecord> changeHistory = new ArrayList<>();
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public void setTasks(List<TravelPlanTask> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        refreshDependencyGraph();
        refreshStatus();
    }

    public void refreshDependencyGraph() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (TravelPlanTask task : tasks) {
            graph.put(task.getTaskId(), new ArrayList<>(task.getDependencies()));
        }
        this.dependencies = graph;
    }

    public Optional<TravelPlanTask> findTask(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return tasks.stream().filter(task -> taskId.equals(task.getTaskId())).findFirst();
    }

    public Optional<TravelPlanTask> findTask(PlanTaskType taskType) {
        if (taskType == null) {
            return Optional.empty();
        }
        return tasks.stream().filter(task -> taskType == task.getTaskType()).findFirst();
    }

    public boolean dependenciesSatisfied(TravelPlanTask task) {
        if (task == null || task.getDependencies() == null || task.getDependencies().isEmpty()) {
            return true;
        }
        return task.getDependencies().stream()
                .map(this::findTask)
                .allMatch(optional -> optional
                        .map(TravelPlanTask::getStatus)
                        .map(PlanTaskStatus::satisfiesDependency)
                        .orElse(false));
    }

    public List<TravelPlanTask> runnableTasks() {
        return tasks.stream()
                .filter(task -> task.getStatus() == PlanTaskStatus.PENDING)
                .filter(this::dependenciesSatisfied)
                .collect(Collectors.toList());
    }

    public void refreshStatus() {
        if (tasks.isEmpty()) {
            status = PlanTaskStatus.PENDING;
            return;
        }
        if (tasks.stream().anyMatch(task -> task.getStatus() == PlanTaskStatus.RUNNING)) {
            status = PlanTaskStatus.RUNNING;
        } else if (tasks.stream().anyMatch(task -> task.getStatus() == PlanTaskStatus.FAILED)) {
            status = PlanTaskStatus.FAILED;
        } else if (tasks.stream().allMatch(task -> task.getStatus().satisfiesDependency())) {
            status = PlanTaskStatus.SUCCESS;
        } else {
            status = PlanTaskStatus.PENDING;
        }
        updatedAt = Instant.now();
    }
}
