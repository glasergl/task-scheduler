package de.glasergl.taskscheduler.backend.model;

public record CreateTaskRequest(String cronExpression, String command) {
}

