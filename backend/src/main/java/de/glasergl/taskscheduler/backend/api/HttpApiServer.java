package de.glasergl.taskscheduler.backend.api;

import de.glasergl.taskscheduler.backend.model.ApiError;
import de.glasergl.taskscheduler.backend.model.CreateTaskRequest;
import de.glasergl.taskscheduler.backend.model.ScheduledTask;
import de.glasergl.taskscheduler.backend.service.TaskSchedulerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpApiServer implements AutoCloseable {
    private static final String TASKS_PATH = "/api/tasks";

    private final TaskSchedulerService taskSchedulerService;
    private final ObjectMapper objectMapper;
    private final HttpServer httpServer;
    private final ExecutorService executorService;

    public HttpApiServer(String host, int port, TaskSchedulerService taskSchedulerService, ObjectMapper objectMapper) throws IOException {
        this.taskSchedulerService = taskSchedulerService;
        this.objectMapper = objectMapper;
        this.httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        this.executorService = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "http-api-worker");
            thread.setDaemon(false);
            return thread;
        });

        httpServer.createContext(TASKS_PATH, this::handleRequest);
        httpServer.setExecutor(executorService);
    }

    public void start() {
        httpServer.start();
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executorService.shutdownNow();
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String suffix = path.length() > TASKS_PATH.length() ? path.substring(TASKS_PATH.length()) : "";

            if (suffix.isBlank() || "/".equals(suffix)) {
                handleCollection(exchange, method);
                return;
            }

            if ("DELETE".equalsIgnoreCase(method) && suffix.startsWith("/")) {
                UUID taskId = UUID.fromString(suffix.substring(1));
                boolean removed = taskSchedulerService.deleteTask(taskId);
                if (removed) {
                    sendNoContent(exchange, 204);
                } else {
                    sendJson(exchange, 404, new ApiError("Task " + taskId + " was not found."));
                }
                return;
            }

            sendJson(exchange, 404, new ApiError("Unknown endpoint."));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, new ApiError(exception.getMessage()));
        } catch (Exception exception) {
            sendJson(exchange, 500, new ApiError("Internal server error: " + exception.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void handleCollection(HttpExchange exchange, String method) throws IOException {
        if ("GET".equalsIgnoreCase(method)) {
            sendJson(exchange, 200, taskSchedulerService.getOverview());
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            CreateTaskRequest createTaskRequest = objectMapper.readValue(exchange.getRequestBody(), CreateTaskRequest.class);
            ScheduledTask createdTask = taskSchedulerService.createTask(createTaskRequest.cronExpression(), createTaskRequest.command());
            sendJson(exchange, 201, createdTask);
            return;
        }

        sendJson(exchange, 405, new ApiError("Unsupported method " + method + "."));
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }

    private void sendNoContent(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
    }
}
