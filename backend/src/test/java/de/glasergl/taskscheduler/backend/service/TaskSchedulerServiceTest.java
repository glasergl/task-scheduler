package de.glasergl.taskscheduler.backend.service;

import de.glasergl.taskscheduler.backend.api.HttpApiServer;
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
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
