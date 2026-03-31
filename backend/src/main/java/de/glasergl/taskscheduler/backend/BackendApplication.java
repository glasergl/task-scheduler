package de.glasergl.taskscheduler.backend;

import de.glasergl.taskscheduler.backend.api.HttpApiServer;
import de.glasergl.taskscheduler.backend.service.CommandRunner;
import de.glasergl.taskscheduler.backend.service.JsonStateStore;
import de.glasergl.taskscheduler.backend.service.JsonSupport;
import de.glasergl.taskscheduler.backend.service.TaskSchedulerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

public final class BackendApplication {
    private static final System.Logger LOGGER = System.getLogger(BackendApplication.class.getName());
    private static final int DEFAULT_PORT = 52398;

    private BackendApplication() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = JsonSupport.createObjectMapper();
        Path storagePath = Path.of("data", "scheduler-state.json");
        int port = resolvePort();
        Supplier<TimeZone> newTaskTimeZoneSupplier = BackendApplication::resolveNewTaskTimeZone;

        Scheduler scheduler = createScheduler();
        TaskSchedulerService taskSchedulerService = new TaskSchedulerService(
                scheduler,
                new JsonStateStore(storagePath, objectMapper),
                new CommandRunner(),
                newTaskTimeZoneSupplier
        );
        taskSchedulerService.start();

        HttpApiServer apiServer = new HttpApiServer("127.0.0.1", port, taskSchedulerService, objectMapper);
        apiServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                apiServer.close();
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to stop HTTP server cleanly.", exception);
            }

            try {
                taskSchedulerService.close();
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to stop scheduler cleanly.", exception);
            }
        }, "task-scheduler-shutdown"));

        LOGGER.log(System.Logger.Level.INFO, "Backend is listening on http://127.0.0.1:" + port);
        LOGGER.log(System.Logger.Level.INFO, "New tasks use the current fixed offset timezone at creation time. Current offset: {0}",
                formatTimeZoneId(newTaskTimeZoneSupplier.get()));
        LOGGER.log(System.Logger.Level.INFO, "Persisted state file: {0}", storagePath.toAbsolutePath());

        new CountDownLatch(1).await();
    }

    private static Scheduler createScheduler() throws SchedulerException {
        return StdSchedulerFactory.getDefaultScheduler();
    }

    private static int resolvePort() {
        String envValue = System.getenv("TASK_SCHEDULER_PORT");
        String propertyValue = System.getProperty("task.scheduler.port");
        String rawValue = propertyValue != null && !propertyValue.isBlank() ? propertyValue : envValue;

        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_PORT;
        }

        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid port: " + rawValue, exception);
        }
    }

    private static TimeZone resolveNewTaskTimeZone() {
        String envValue = System.getenv("TASK_SCHEDULER_TIMEZONE");
        String propertyValue = System.getProperty("task.scheduler.timezone");
        String rawValue = propertyValue != null && !propertyValue.isBlank() ? propertyValue : envValue;

        if (rawValue == null || rawValue.isBlank()) {
            return TimeZone.getTimeZone(ZoneId.systemDefault().getRules().getOffset(java.time.Instant.now()));
        }

        try {
            return TimeZone.getTimeZone(ZoneId.of(rawValue));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid schedule timezone: " + rawValue, exception);
        }
    }

    private static String formatTimeZoneId(TimeZone timeZone) {
        String zoneId = timeZone.toZoneId().getId();
        if (zoneId.startsWith("GMT+") || zoneId.startsWith("GMT-")) {
            return zoneId.substring(3);
        }
        return zoneId;
    }
}
