package de.glasergl.taskscheduler.backend.service;

import de.glasergl.taskscheduler.backend.job.CommandExecutionJob;
import de.glasergl.taskscheduler.backend.model.ExecutionRecord;
import de.glasergl.taskscheduler.backend.model.ExecutionStatus;
import de.glasergl.taskscheduler.backend.model.PersistedState;
import de.glasergl.taskscheduler.backend.model.RunningTaskView;
import de.glasergl.taskscheduler.backend.model.ScheduledTask;
import de.glasergl.taskscheduler.backend.model.SchedulerOverview;
import de.glasergl.taskscheduler.backend.model.TaskSummary;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class TaskSchedulerService implements AutoCloseable {
    public static final String SCHEDULER_CONTEXT_KEY = "taskSchedulerService";
    public static final TimeZone LEGACY_DEFAULT_SCHEDULE_TIME_ZONE = TimeZone.getTimeZone(ZoneId.of("+01:00"));

    private static final String JOB_GROUP = "scheduled-tasks";
    private static final int MAX_HISTORY_SIZE = 500;
    private static final int MAX_RECENT_EXECUTIONS = 50;

    private static final System.Logger LOGGER = System.getLogger(TaskSchedulerService.class.getName());

    private final Scheduler scheduler;
    private final JsonStateStore jsonStateStore;
    private final CommandRunner commandRunner;
    private final Supplier<TimeZone> newTaskTimeZoneSupplier;
    private final Map<UUID, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<UUID, RunningTaskView> runningTasks = new ConcurrentHashMap<>();
    private final Deque<ExecutionRecord> executionHistory = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService persistenceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "state-persistence");
        thread.setDaemon(true);
        return thread;
    });

    public TaskSchedulerService(Scheduler scheduler, JsonStateStore jsonStateStore) {
        this(scheduler, jsonStateStore, new CommandRunner(), TaskSchedulerService::resolveCurrentSystemOffsetTimeZone);
    }

    public TaskSchedulerService(Scheduler scheduler, JsonStateStore jsonStateStore, CommandRunner commandRunner) {
        this(scheduler, jsonStateStore, commandRunner, TaskSchedulerService::resolveCurrentSystemOffsetTimeZone);
    }

    public TaskSchedulerService(
            Scheduler scheduler,
            JsonStateStore jsonStateStore,
            CommandRunner commandRunner,
            Supplier<TimeZone> newTaskTimeZoneSupplier
    ) {
        this.scheduler = scheduler;
        this.jsonStateStore = jsonStateStore;
        this.commandRunner = commandRunner;
        this.newTaskTimeZoneSupplier = newTaskTimeZoneSupplier;
    }

    public void start() throws SchedulerException, IOException {
        scheduler.getContext().put(SCHEDULER_CONTEXT_KEY, this);
        restorePersistedState();
        scheduler.start();
        persistenceExecutor.scheduleWithFixedDelay(this::persistQuietly, 15, 15, TimeUnit.SECONDS);
    }

    public SchedulerOverview getOverview() {
        List<TaskSummary> taskSummaries = scheduledTasks.values().stream()
                .sorted(Comparator.comparing(ScheduledTask::createdAt))
                .map(this::buildTaskSummary)
                .toList();

        List<RunningTaskView> runningTaskViews = runningTasks.values().stream()
                .sorted(Comparator.comparing(RunningTaskView::startedAt))
                .toList();

        List<ExecutionRecord> recentExecutions = executionHistory.stream()
                .limit(MAX_RECENT_EXECUTIONS)
                .toList();

        return new SchedulerOverview(taskSummaries, runningTaskViews, recentExecutions);
    }

    public ScheduledTask createTask(String cronExpression, String command) {
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                requireValidCronExpression(cronExpression),
                requireCommand(command),
                Instant.now(),
                serializeScheduleTimeZone(newTaskTimeZoneSupplier.get())
        );
        scheduleTask(task);
        scheduledTasks.put(task.id(), task);
        persistQuietly();
        return task;
    }

    public ScheduledTask updateTask(UUID taskId, String cronExpression, String command) {
        ScheduledTask existingTask = scheduledTasks.get(taskId);
        if (existingTask == null) {
            return null;
        }

        String normalizedCron = requireValidCronExpression(cronExpression);
        String normalizedCommand = requireCommand(command);
        String scheduleTimeZone = existingTask.scheduleTimeZone();
        if (!existingTask.cronExpression().equals(normalizedCron)) {
            scheduleTimeZone = serializeScheduleTimeZone(newTaskTimeZoneSupplier.get());
        }

        ScheduledTask updatedTask = new ScheduledTask(
                existingTask.id(),
                normalizedCron,
                normalizedCommand,
                existingTask.createdAt(),
                scheduleTimeZone
        );

        if (requiresReschedule(existingTask, updatedTask)) {
            rescheduleTask(updatedTask);
        }

        scheduledTasks.put(taskId, updatedTask);
        persistQuietly();
        return updatedTask;
    }

    public boolean deleteTask(UUID taskId) {
        ScheduledTask removedTask = scheduledTasks.remove(taskId);
        if (removedTask == null) {
            return false;
        }

        try {
            scheduler.deleteJob(jobKey(taskId));
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Failed to delete Quartz job for task " + taskId, exception);
        }

        persistQuietly();
        return true;
    }

    public void executeTask(UUID taskId) {
        ScheduledTask task = scheduledTasks.get(taskId);
        if (task == null) {
            LOGGER.log(System.Logger.Level.WARNING, "Ignoring trigger for missing task {0}", taskId);
            return;
        }

        UUID executionId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Process process = null;

        try {
            process = commandRunner.start(task.command());
            RunningTaskView runningTask = new RunningTaskView(
                    executionId,
                    task.id(),
                    task.cronExpression(),
                    task.command(),
                    startedAt,
                    process.pid()
            );
            runningTasks.put(executionId, runningTask);

            int exitCode = process.waitFor();
            recordExecution(new ExecutionRecord(
                    executionId,
                    task.id(),
                    task.cronExpression(),
                    task.command(),
                    startedAt,
                    Instant.now(),
                    exitCode,
                    exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                    exitCode == 0 ? "Process finished successfully." : "Process exited with code " + exitCode + "."
            ));
        } catch (IOException exception) {
            recordExecution(new ExecutionRecord(
                    executionId,
                    task.id(),
                    task.cronExpression(),
                    task.command(),
                    startedAt,
                    Instant.now(),
                    null,
                    ExecutionStatus.FAILED_TO_START,
                    exception.getMessage()
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            recordExecution(new ExecutionRecord(
                    executionId,
                    task.id(),
                    task.cronExpression(),
                    task.command(),
                    startedAt,
                    Instant.now(),
                    null,
                    ExecutionStatus.INTERRUPTED,
                    "Execution was interrupted."
            ));
        } finally {
            runningTasks.remove(executionId);
            persistQuietly();
        }
    }

    @Override
    public void close() {
        persistenceExecutor.shutdownNow();
        persistQuietly();
        try {
            scheduler.shutdown(true);
        } catch (SchedulerException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to shut down Quartz scheduler cleanly.", exception);
        }
    }

    private void restorePersistedState() throws IOException {
        PersistedState persistedState = jsonStateStore.load();
        for (ScheduledTask task : persistedState.tasks()) {
            scheduledTasks.put(task.id(), task);
            scheduleTask(task);
        }

        for (ExecutionRecord executionRecord : persistedState.executionHistory()) {
            executionHistory.addLast(executionRecord);
        }
        trimExecutionHistory();
    }

    private void scheduleTask(ScheduledTask task) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("taskId", task.id().toString());

        JobDetail jobDetail = JobBuilder.newJob(CommandExecutionJob.class)
                .withIdentity(jobKey(task.id()))
                .usingJobData(jobDataMap)
                .build();

        CronTrigger cronTrigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(task.id()))
                .forJob(jobDetail)
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(task.cronExpression())
                                .inTimeZone(resolveScheduleTimeZone(task))
                                .withMisfireHandlingInstructionDoNothing()
                )
                .build();

        try {
            scheduler.scheduleJob(jobDetail, cronTrigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Failed to schedule task " + task.id(), exception);
        }
    }

    private void rescheduleTask(ScheduledTask task) {
        CronTrigger cronTrigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(task.id()))
                .forJob(jobKey(task.id()))
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(task.cronExpression())
                                .inTimeZone(resolveScheduleTimeZone(task))
                                .withMisfireHandlingInstructionDoNothing()
                )
                .build();

        try {
            scheduler.rescheduleJob(triggerKey(task.id()), cronTrigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Failed to reschedule task " + task.id(), exception);
        }
    }

    private TaskSummary buildTaskSummary(ScheduledTask task) {
        Trigger trigger;
        try {
            trigger = scheduler.getTrigger(triggerKey(task.id()));
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Failed to inspect trigger for task " + task.id(), exception);
        }

        ExecutionRecord lastExecution = executionHistory.stream()
                .filter(record -> record.taskId().equals(task.id()))
                .findFirst()
                .orElse(null);

        Instant nextRunAt = trigger != null && trigger.getNextFireTime() != null ? trigger.getNextFireTime().toInstant() : null;
        Instant previousRunAtFromTrigger = trigger != null && trigger.getPreviousFireTime() != null
                ? trigger.getPreviousFireTime().toInstant()
                : null;
        Instant previousRunAtFromHistory = lastExecution != null ? lastExecution.startedAt() : null;
        Instant previousRunAt = latestInstant(previousRunAtFromTrigger, previousRunAtFromHistory);

        return new TaskSummary(
                task.id(),
                task.cronExpression(),
                task.command(),
                task.createdAt(),
                task.scheduleTimeZone(),
                nextRunAt,
                previousRunAt,
                lastExecution != null ? lastExecution.exitCode() : null,
                lastExecution != null ? lastExecution.status() : null
        );
    }

    private void recordExecution(ExecutionRecord executionRecord) {
        executionHistory.addFirst(executionRecord);
        trimExecutionHistory();
    }

    private void trimExecutionHistory() {
        while (executionHistory.size() > MAX_HISTORY_SIZE) {
            executionHistory.removeLast();
        }
    }

    private void persistQuietly() {
        try {
            jsonStateStore.save(scheduledTasks.values(), executionHistory);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to persist task scheduler state.", exception);
        }
    }

    private JobKey jobKey(UUID taskId) {
        return JobKey.jobKey(taskId.toString(), JOB_GROUP);
    }

    private TriggerKey triggerKey(UUID taskId) {
        return TriggerKey.triggerKey(taskId.toString(), JOB_GROUP);
    }

    private TimeZone resolveScheduleTimeZone(ScheduledTask task) {
        if (task.scheduleTimeZone() == null || task.scheduleTimeZone().isBlank()) {
            return LEGACY_DEFAULT_SCHEDULE_TIME_ZONE;
        }

        return TimeZone.getTimeZone(ZoneId.of(task.scheduleTimeZone()));
    }

    private static TimeZone resolveCurrentSystemOffsetTimeZone() {
        return TimeZone.getTimeZone(ZoneId.systemDefault().getRules().getOffset(Instant.now()));
    }

    private static String serializeScheduleTimeZone(TimeZone scheduleTimeZone) {
        String zoneId = scheduleTimeZone.toZoneId().getId();
        if (zoneId.startsWith("GMT+") || zoneId.startsWith("GMT-")) {
            return zoneId.substring(3);
        }
        return zoneId;
    }

    private static String requireValidCronExpression(String cronExpression) {
        String normalizedCron = CronExpressionNormalizer.normalize(cronExpression);
        if (!CronExpression.isValidExpression(normalizedCron)) {
            throw new IllegalArgumentException("Invalid Quartz cron expression: " + normalizedCron);
        }
        return normalizedCron;
    }

    private static String requireCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Command must not be blank.");
        }
        return command.trim();
    }

    private static boolean requiresReschedule(ScheduledTask existingTask, ScheduledTask updatedTask) {
        return !existingTask.cronExpression().equals(updatedTask.cronExpression())
                || !Objects.equals(existingTask.scheduleTimeZone(), updatedTask.scheduleTimeZone());
    }

    private static Instant latestInstant(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}
