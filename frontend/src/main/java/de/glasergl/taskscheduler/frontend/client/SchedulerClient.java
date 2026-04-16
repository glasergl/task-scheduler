package de.glasergl.taskscheduler.frontend.client;

import de.glasergl.taskscheduler.frontend.model.ApiCreateTaskRequest;
import de.glasergl.taskscheduler.frontend.model.ApiError;
import de.glasergl.taskscheduler.frontend.model.ApiScheduledTask;
import de.glasergl.taskscheduler.frontend.model.ApiSchedulerOverview;
import de.glasergl.taskscheduler.frontend.model.ApiUpdateTaskRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class SchedulerClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SchedulerClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), JsonSupport.createObjectMapper());
    }

    public SchedulerClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ApiSchedulerOverview fetchOverview(String baseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(tasksUri(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response);
        return objectMapper.readValue(response.body(), ApiSchedulerOverview.class);
    }

    public ApiScheduledTask createTask(String baseUrl, String cronExpression, String command) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(tasksUri(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(new ApiCreateTaskRequest(cronExpression, command)),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response);
        return objectMapper.readValue(response.body(), ApiScheduledTask.class);
    }

    public void deleteTask(String baseUrl, UUID taskId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(taskUri(baseUrl, taskId))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 204) {
            ensureSuccess(response);
        }
    }

    public ApiScheduledTask updateTask(String baseUrl, UUID taskId, String cronExpression, String command) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(taskUri(baseUrl, taskId))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(new ApiUpdateTaskRequest(cronExpression, command)),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response);
        return objectMapper.readValue(response.body(), ApiScheduledTask.class);
    }

    private void ensureSuccess(HttpResponse<String> response) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        String message = response.body();
        try {
            ApiError error = objectMapper.readValue(response.body(), ApiError.class);
            if (error.error() != null && !error.error().isBlank()) {
                message = error.error();
            }
        } catch (Exception ignored) {
            // Fall back to the raw response body if JSON parsing fails.
        }

        throw new IOException("Request failed with HTTP " + statusCode + ": " + message);
    }

    private URI tasksUri(String baseUrl) {
        return URI.create(normalizeBaseUrl(baseUrl) + "/api/tasks");
    }

    private URI taskUri(String baseUrl, UUID taskId) {
        return URI.create(normalizeBaseUrl(baseUrl) + "/api/tasks/" + taskId);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Backend URL must not be blank.");
        }

        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
