package de.glasergl.taskscheduler.backend.model;

public record UpdateTaskRequest(String cronExpression, String command) {
}
