package de.glasergl.taskscheduler.backend.model;

import java.time.Instant;
import java.util.UUID;

public record RunningTaskView(
        UUID executionId,
        UUID taskId,
        String cronExpression,
        String command,
        Instant startedAt,
        Long processId
) {
}

