package de.glasergl.taskscheduler.backend.model;

import java.time.Instant;
import java.util.UUID;

public record ScheduledTask(UUID id, String cronExpression, String command, Instant createdAt, String scheduleTimeZone) {
}
