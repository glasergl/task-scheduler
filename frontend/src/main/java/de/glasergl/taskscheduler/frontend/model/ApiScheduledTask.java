package de.glasergl.taskscheduler.frontend.model;

import java.time.Instant;
import java.util.UUID;

public record ApiScheduledTask(UUID id, String cronExpression, String command, Instant createdAt, String scheduleTimeZone) {
}
