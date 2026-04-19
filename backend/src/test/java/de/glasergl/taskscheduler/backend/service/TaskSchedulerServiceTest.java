package de.glasergl.taskscheduler.backend.service;

import de.glasergl.taskscheduler.backend.api.HttpApiServer;
import de.glasergl.taskscheduler.backend.model.ExecutionRecord;
import de.glasergl.taskscheduler.backend.model.ExecutionStatus;
import de.glasergl.taskscheduler.backend.model.ScheduledTask;
import de.glasergl.taskscheduler.backend.model.SchedulerOverview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulerServiceTest {

    @Test
    void createTaskShouldSucceed(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        try {
            service.start();

            assertDoesNotThrow(() -> service.createTask("*/30 * * * *", "echo hello"));

            SchedulerOverview overview = service.getOverview();
            assertEquals(1, overview.scheduledTasks().size());
        } finally {
            service.close();
        }
    }

    @Test
    void newTasksShouldKeepTheCurrentFixedOffsetAtCreationTime(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper),
                new CommandRunner(),
                () -> TimeZone.getTimeZone(ZoneId.of("+02:00"))
        );

        try {
            service.start();

            var task = service.createTask("0 0 0/2 * * ?", "echo hello");
            CronTrigger trigger = (CronTrigger) scheduler.getTrigger(TriggerKey.triggerKey(task.id().toString(), "scheduled-tasks"));

            assertEquals("+02:00", task.scheduleTimeZone());
            assertEquals(7_200_000, trigger.getTimeZone().getRawOffset());
            assertFalse(trigger.getTimeZone().useDaylightTime());
        } finally {
            service.close();
        }
    }

    @Test
    void updateTaskShouldChangeCronAndCommandAndReschedule(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper),
                new CommandRunner(),
                () -> TimeZone.getTimeZone(ZoneId.of("+03:00"))
        );

        try {
            service.start();

            ScheduledTask createdTask = service.createTask("0 0 8 * * ?", "echo before");
            ScheduledTask updatedTask = service.updateTask(createdTask.id(), "0 15 9 * * ?", "echo after");
            CronTrigger trigger = (CronTrigger) scheduler.getTrigger(TriggerKey.triggerKey(createdTask.id().toString(), "scheduled-tasks"));

            assertEquals(createdTask.id(), updatedTask.id());
            assertEquals(createdTask.createdAt(), updatedTask.createdAt());
            assertEquals("0 15 9 * * ?", updatedTask.cronExpression());
            assertEquals("echo after", updatedTask.command());
            assertEquals("+03:00", updatedTask.scheduleTimeZone());
            assertEquals("0 15 9 * * ?", trigger.getCronExpression());
            assertEquals(10_800_000, trigger.getTimeZone().getRawOffset());
        } finally {
            service.close();
        }
    }

    @Test
    void updateTaskShouldKeepTimezoneWhenOnlyCommandChanges(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper),
                new CommandRunner(),
                () -> TimeZone.getTimeZone(ZoneId.of("+03:00"))
        );

        try {
            service.start();

            ScheduledTask createdTask = service.createTask("0 0 8 * * ?", "echo before");
            ScheduledTask updatedTask = service.updateTask(createdTask.id(), "0 0 8 * * ?", "echo after");

            assertEquals(createdTask.scheduleTimeZone(), updatedTask.scheduleTimeZone());
            assertEquals("echo after", updatedTask.command());
        } finally {
            service.close();
        }
    }

    @Test
    void disableTaskShouldKeepTaskButRemoveItsSchedule(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        try {
            service.start();

            ScheduledTask createdTask = service.createTask("0 0 8 * * ?", "echo before");
            ScheduledTask disabledTask = service.disableTask(createdTask.id());

            assertFalse(disabledTask.enabled());
            assertNull(scheduler.getTrigger(TriggerKey.triggerKey(createdTask.id().toString(), "scheduled-tasks")));
            assertFalse(service.getOverview().scheduledTasks().getFirst().enabled());
            assertNull(service.getOverview().scheduledTasks().getFirst().nextRunAt());
        } finally {
            service.close();
        }
    }

    @Test
    void enableTaskShouldRescheduleDisabledTask(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        try {
            service.start();

            ScheduledTask createdTask = service.createTask("0 0 8 * * ?", "echo before");
            service.disableTask(createdTask.id());
            ScheduledTask enabledTask = service.enableTask(createdTask.id());

            assertTrue(enabledTask.enabled());
            assertNotNull(scheduler.getTrigger(TriggerKey.triggerKey(createdTask.id().toString(), "scheduled-tasks")));
            assertTrue(service.getOverview().scheduledTasks().getFirst().enabled());
            assertNotNull(service.getOverview().scheduledTasks().getFirst().nextRunAt());
        } finally {
            service.close();
        }
    }

    @Test
    void disabledTaskShouldNotExecuteWhenTriggered(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        try {
            service.start();

            ScheduledTask createdTask = service.createTask("0 0 8 * * ?", "echo hello");
            service.disableTask(createdTask.id());
            service.executeTask(createdTask.id());

            assertTrue(service.getOverview().recentExecutions().isEmpty());
        } finally {
            service.close();
        }
    }

    @Test
    @DisplayName("A task created at 02:00 during MESZ should later appear at 01:00 during MEZ")
    void summerAnchoredScheduleShouldAppearOneHourEarlierAfterWinterSwitch() throws Exception {
        CronExpression cronExpression = new CronExpression("0 0 2 * * ?");
        cronExpression.setTimeZone(TimeZone.getTimeZone(ZoneId.of("+02:00")));

        Instant afterSummerOccurrence = Instant.parse("2026-10-25T00:01:00Z");
        Instant nextFireAt = cronExpression.getNextValidTimeAfter(Date.from(afterSummerOccurrence)).toInstant();
        ZonedDateTime berlinView = nextFireAt.atZone(ZoneId.of("Europe/Berlin"));

        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), nextFireAt);
        assertEquals(1, berlinView.getHour());
        assertEquals(ZoneOffset.ofHours(1), berlinView.getOffset());
    }

    @Test
    void previousRunShouldBeRestoredFromExecutionHistoryAfterRestart(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Path storagePath = tempDir.resolve("state.json");
        JsonStateStore jsonStateStore = new JsonStateStore(storagePath, objectMapper);

        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                "0 0/30 * * * ?",
                "echo hello",
                Instant.parse("2026-03-31T18:00:00Z"),
                "+02:00"
        );
        ExecutionRecord executionRecord = new ExecutionRecord(
                UUID.randomUUID(),
                task.id(),
                task.cronExpression(),
                task.command(),
                Instant.parse("2026-03-31T22:00:00Z"),
                Instant.parse("2026-03-31T22:00:01Z"),
                0,
                ExecutionStatus.SUCCEEDED,
                "Process finished successfully."
        );
        jsonStateStore.save(List.of(task), List.of(executionRecord));

        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(scheduler, jsonStateStore);

        try {
            service.start();

            SchedulerOverview overview = service.getOverview();

            assertEquals(1, overview.scheduledTasks().size());
            assertEquals(executionRecord.startedAt(), overview.scheduledTasks().get(0).previousRunAt());
            assertEquals(ExecutionStatus.SUCCEEDED, overview.scheduledTasks().get(0).lastStatus());
        } finally {
            service.close();
        }
    }

    @Test
    void persistedTasksWithoutEnabledFieldShouldDefaultToEnabled(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Path storagePath = tempDir.resolve("state.json");
        Files.writeString(storagePath, """
                {
                  "tasks" : [ {
                    "id" : "a5b1917c-6e48-462f-bec8-676b6cdb00f8",
                    "cronExpression" : "0 0/30 * * * ?",
                    "command" : "echo hello",
                    "createdAt" : "2026-03-31T18:00:00Z",
                    "scheduleTimeZone" : "+02:00"
                  } ],
                  "executionHistory" : [ ]
                }
                """);

        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(storagePath, objectMapper)
        );

        try {
            service.start();

            SchedulerOverview overview = service.getOverview();

            assertTrue(overview.scheduledTasks().getFirst().enabled());
            assertNotNull(scheduler.getTrigger(TriggerKey.triggerKey(
                    overview.scheduledTasks().getFirst().id().toString(),
                    "scheduled-tasks"
            )));
        } finally {
            service.close();
        }
    }

    @Test
    void postEndpointShouldCreateTask(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        int port;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            port = serverSocket.getLocalPort();
        }

        HttpApiServer httpApiServer = new HttpApiServer("127.0.0.1", port, service, objectMapper);

        try {
            service.start();
            httpApiServer.start();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"cronExpression":"*/30 * * * *","command":"echo hello"}
                            """))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(201, response.statusCode(), response.body());
            assertEquals(1, service.getOverview().scheduledTasks().size());
        } finally {
            httpApiServer.close();
            service.close();
        }
    }

    @Test
    void postEndpointShouldReturnJsonErrorForInvalidCron(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        int port;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            port = serverSocket.getLocalPort();
        }

        HttpApiServer httpApiServer = new HttpApiServer("127.0.0.1", port, service, objectMapper);

        try {
            service.start();
            httpApiServer.start();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"cronExpression":"1 2 3 4","command":"echo hello"}
                            """))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("Cron expression"));
        } finally {
            httpApiServer.close();
            service.close();
        }
    }

    @Test
    void putEndpointShouldUpdateTask(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        int port;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            port = serverSocket.getLocalPort();
        }

        HttpApiServer httpApiServer = new HttpApiServer("127.0.0.1", port, service, objectMapper);

        try {
            service.start();
            ScheduledTask task = service.createTask("*/30 * * * *", "echo hello");
            httpApiServer.start();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks/" + task.id()))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {"cronExpression":"15 * * * *","command":"echo updated"}
                            """))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            assertEquals("echo updated", service.getOverview().scheduledTasks().getFirst().command());
            assertEquals("0 15 * * * ?", service.getOverview().scheduledTasks().getFirst().cronExpression());
        } finally {
            httpApiServer.close();
            service.close();
        }
    }

    @Test
    void postDisableAndEnableEndpointsShouldToggleTaskState(@TempDir Path tempDir) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        TaskSchedulerService service = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(tempDir.resolve("state.json"), objectMapper)
        );

        int port;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            port = serverSocket.getLocalPort();
        }

        HttpApiServer httpApiServer = new HttpApiServer("127.0.0.1", port, service, objectMapper);

        try {
            service.start();
            ScheduledTask task = service.createTask("*/30 * * * *", "echo hello");
            httpApiServer.start();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> disableResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks/" + task.id() + "/disable"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, disableResponse.statusCode(), disableResponse.body());
            assertFalse(service.getOverview().scheduledTasks().getFirst().enabled());

            HttpResponse<String> enableResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks/" + task.id() + "/enable"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, enableResponse.statusCode(), enableResponse.body());
            assertTrue(service.getOverview().scheduledTasks().getFirst().enabled());
        } finally {
            httpApiServer.close();
            service.close();
        }
    }
}
