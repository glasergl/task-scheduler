package de.glasergl.taskscheduler.frontend.model;

import java.time.Instant;
import java.util.UUID;

public record ApiExecutionRecord(
        UUID executionId,
        UUID taskId,
        String cronExpression,
        String command,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        ApiExecutionStatus status,
        String message
) {
}

