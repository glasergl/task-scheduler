package de.glasergl.taskscheduler.backend.model;

import java.util.List;

public record PersistedState(List<ScheduledTask> tasks, List<ExecutionRecord> executionHistory) {
    public PersistedState {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        executionHistory = executionHistory == null ? List.of() : List.copyOf(executionHistory);
    }
}

