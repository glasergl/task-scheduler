package de.glasergl.taskscheduler.frontend.model;

import java.util.List;

public record ApiSchedulerOverview(
        List<ApiTaskSummary> scheduledTasks,
        List<ApiRunningTask> runningTasks,
        List<ApiExecutionRecord> recentExecutions
) {
    public ApiSchedulerOverview {
        scheduledTasks = scheduledTasks == null ? List.of() : List.copyOf(scheduledTasks);
        runningTasks = runningTasks == null ? List.of() : List.copyOf(runningTasks);
        recentExecutions = recentExecutions == null ? List.of() : List.copyOf(recentExecutions);
    }
}

