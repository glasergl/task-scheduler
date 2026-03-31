package de.glasergl.taskscheduler.frontend.model;

public record ApiCreateTaskRequest(String cronExpression, String command) {
}

