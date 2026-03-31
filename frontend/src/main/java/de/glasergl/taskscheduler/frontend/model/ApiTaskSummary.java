package de.glasergl.taskscheduler.frontend.model;

import java.time.Instant;
import java.util.UUID;

public record ApiTaskSummary(
        UUID id,
        String cronExpression,
        String command,
        Instant createdAt,
        String scheduleTimeZone,
        Instant nextRunAt,
        Instant previousRunAt,
        Integer lastExitCode,
        ApiExecutionStatus lastStatus
) {
}
