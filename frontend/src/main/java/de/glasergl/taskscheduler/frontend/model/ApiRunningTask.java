package de.glasergl.taskscheduler.frontend.model;

import java.time.Instant;
import java.util.UUID;

public record ApiRunningTask(
        UUID executionId,
        UUID taskId,
        String cronExpression,
        String command,
        Instant startedAt,
        Long processId
) {
}

