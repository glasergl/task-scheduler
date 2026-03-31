package de.glasergl.taskscheduler.backend.model;

import java.util.List;

public record SchedulerOverview(
        List<TaskSummary> scheduledTasks,
        List<RunningTaskView> runningTasks,
        List<ExecutionRecord> recentExecutions
) {
}

