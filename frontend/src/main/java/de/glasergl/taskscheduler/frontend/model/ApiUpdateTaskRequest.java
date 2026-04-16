package de.glasergl.taskscheduler.frontend.model;

public record ApiUpdateTaskRequest(String cronExpression, String command) {
}
