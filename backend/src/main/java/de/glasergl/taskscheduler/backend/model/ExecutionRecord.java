package de.glasergl.taskscheduler.backend.model;

import java.time.Instant;
import java.util.UUID;

public record ExecutionRecord(
        UUID executionId,
        UUID taskId,
        String cronExpression,
        String command,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        ExecutionStatus status,
        String message
) {
}

