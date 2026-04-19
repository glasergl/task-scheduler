package de.glasergl.taskscheduler.backend.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record ScheduledTask(
        UUID id,
        String cronExpression,
        String command,
        Instant createdAt,
        String scheduleTimeZone,
        boolean enabled
) {
    public ScheduledTask(UUID id, String cronExpression, String command, Instant createdAt, String scheduleTimeZone) {
        this(id, cronExpression, command, createdAt, scheduleTimeZone, true);
    }

    @JsonCreator
    public ScheduledTask(
            @JsonProperty("id") UUID id,
            @JsonProperty("cronExpression") String cronExpression,
            @JsonProperty("command") String command,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("scheduleTimeZone") String scheduleTimeZone,
            @JsonProperty("enabled") Boolean enabled
    ) {
        this(id, cronExpression, command, createdAt, scheduleTimeZone, enabled == null || enabled);
    }
}
