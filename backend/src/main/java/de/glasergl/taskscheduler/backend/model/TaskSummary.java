package de.glasergl.taskscheduler.backend.model;

import java.time.Instant;
import java.util.UUID;

public record TaskSummary(
        UUID id,
        String cronExpression,
        String command,
        Instant createdAt,
        String scheduleTimeZone,
        boolean enabled,
        Instant nextRunAt,
        Instant previousRunAt,
        Integer lastExitCode,
        ExecutionStatus lastStatus
) {
}
