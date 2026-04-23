package com.travelagent.agent.planner;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.service.agent.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionDispatcher.class);

    @Autowired private TaskMapper taskMapper;
    @Autowired private AgentService agentService;

    @Autowired
    @Qualifier("agentTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public boolean dispatchTask(String taskUuid, String trigger) {
        if (!inFlight.add(taskUuid)) {
            log.debug("Task {} already in-flight, skipping trigger={}", taskUuid, trigger);
            return false;
        }

        log.info("Dispatching task uuid={} trigger={}", taskUuid, trigger);
        executor.execute(() -> {
            try {
                agentService.executeTask(taskUuid);
            } catch (Exception e) {
                log.error("Uncaught exception executing task {} trigger={}", taskUuid, trigger, e);
            } finally {
                inFlight.remove(taskUuid);
            }
        });
        return true;
    }

    public void dispatchByStatus(String statusCode, int limit) {
        List<Task> tasks = taskMapper.findByStatus(statusCode, limit);
        if (tasks.isEmpty()) {
            return;
        }

        for (Task task : tasks) {
            dispatchTask(task.getTaskUuid(), "poll:" + statusCode);
        }
    }
}
